package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.getRoleId
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequisitionRoutesTest {

    @BeforeAll
    fun setup() {
        TestHelper.setupDatabase()
    }

    @AfterAll
    fun teardown() {
        TestHelper.teardownDatabase()
    }

    @BeforeEach
    fun cleanTables() {
        TestHelper.cleanTables()
    }

    private suspend fun ApplicationTestBuilder.createUnit(
        token: String,
        tuntasId: String,
        name: String = "Vilkai"
    ): String {
        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "type": "SKAUTU_DRAUGOVE" }""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.registerUserWithRole(
        token: String,
        tuntasId: String,
        roleName: String,
        email: String,
        organizationalUnitId: String? = null
    ): Pair<String, String> {
        val roleId = getRoleId(tuntasId, roleName)
        val unitField = organizationalUnitId?.let { """, "organizationalUnitId": "$it"""" }.orEmpty()
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId"$unitField, "expiresInHours": 48 }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "name": "Test",
                    "surname": "Narys",
                    "email": "$email",
                    "password": "testas123",
                    "inviteCode": "$inviteCode"
                }
                """.trimIndent()
            )
        }
        val body = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
        return body["token"]!!.jsonPrimitive.content to body["userId"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.assignMember(
        token: String,
        tuntasId: String,
        unitId: String,
        userId: String
    ) {
        client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$userId", "assignmentType": "MEMBER" }""")
        }
    }

    private suspend fun ApplicationTestBuilder.createRequisition(
        token: String,
        tuntasId: String,
        requestingUnitId: String?
    ) = client.post("/api/requisitions") {
        contentType(ContentType.Application.Json)
        header("Authorization", "Bearer $token")
        header("X-Tuntas-Id", tuntasId)
        val unitField = requestingUnitId?.let { """"requestingUnitId": "$it",""" }.orEmpty()
        setBody(
            """
            {
                $unitField
                "items": [
                    { "itemName": "Nauja palapine", "quantity": 2 }
                ]
            }
            """.trimIndent()
        )
    }

    @Test
    fun `unit leader creates own unit requisition as approved`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)
        val (leaderToken, _) = registerUserWithRole(
            token = token,
            tuntasId = tuntasId,
            roleName = "Draugininkas",
            email = "leader@test.com",
            organizationalUnitId = unitId
        )

        val response = createRequisition(leaderToken, tuntasId, unitId)

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("APPROVED", body["status"]?.jsonPrimitive?.content)
        assertEquals("APPROVED", body["unitReviewStatus"]?.jsonPrimitive?.content)
        assertEquals("NOT_REQUIRED", body["topLevelReviewStatus"]?.jsonPrimitive?.content)
        val item = body["items"]!!.jsonArray.first().jsonObject
        assertEquals("2", item["quantityApproved"]?.jsonPrimitive?.content)
    }

    @Test
    fun `regular member creates own unit requisition as pending`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)
        val (memberToken, memberId) = registerUserWithRole(token, tuntasId, "Skautas", "member@test.com")
        assignMember(token, tuntasId, unitId, memberId)

        val response = createRequisition(memberToken, tuntasId, unitId)

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("SUBMITTED", body["status"]?.jsonPrimitive?.content)
        assertEquals("PENDING", body["unitReviewStatus"]?.jsonPrimitive?.content)
        assertEquals("NOT_REQUIRED", body["topLevelReviewStatus"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tuntas level requisition can be created without unit`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = createRequisition(token, tuntasId, requestingUnitId = null)

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(null, body["requestingUnitId"])
        assertEquals("SKIPPED", body["unitReviewStatus"]?.jsonPrimitive?.content)
        assertEquals("PENDING", body["topLevelReviewStatus"]?.jsonPrimitive?.content)
    }

    @Test
    fun `regular member cannot create tuntas level requisition`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (memberToken, _) = registerUserWithRole(token, tuntasId, "Skautas", "member@test.com")

        val response = createRequisition(memberToken, tuntasId, requestingUnitId = null)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `vadovas rank cannot create tuntas level requisition`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (vadovasToken, _) = registerUserWithRole(token, tuntasId, "Vadovas", "vadovas-rank@test.com")

        val response = createRequisition(vadovasToken, tuntasId, requestingUnitId = null)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
