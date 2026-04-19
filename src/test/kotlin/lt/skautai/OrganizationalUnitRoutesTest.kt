package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganizationalUnitRoutesTest {

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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun ApplicationTestBuilder.createUnit(
        token: String,
        tuntasId: String,
        name: String = "Vilkai",
        type: String = "VILKU_DRAUGOVE"
    ): String {
        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "type": "$type" }""")
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.registerSecondUser(
        token: String,
        tuntasId: String,
        roleName: String,
        email: String = "second@test.com"
    ): Pair<String, String> {
        val roleId = TestHelper.getRoleId(tuntasId, roleName)
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId", "expiresAt": "2099-01-01T00:00:00Z" }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Second",
                    "surname": "User",
                    "email": "$email",
                    "password": "test123",
                    "inviteCode": "$inviteCode"
                }
            """.trimIndent())
        }
        val body = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
        return body["token"]!!.jsonPrimitive.content to body["userId"]!!.jsonPrimitive.content
    }

    // ── Unit CRUD ─────────────────────────────────────────────────────────────

    @Test
    fun `create unit returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vilkai", "type": "VILKU_DRAUGOVE" }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Vilkai", body["name"]?.jsonPrimitive?.content)
        assertEquals("VILKU_DRAUGOVE", body["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create VYR unit with subtype returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vyr. skautai", "type": "VYR_SKAUTU_VIENETAS", "subType": "DRAUGOVE" }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("VYR_SKAUTU_VIENETAS", body["type"]?.jsonPrimitive?.content)
        assertEquals("DRAUGOVE", body["subType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create unit with invalid type returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vilkai", "type": "INVALID" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `create unit with subtype on non-VYR type returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vilkai", "type": "VILKU_DRAUGOVE", "subType": "DRAUGOVE" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `create unit without token returns 401`() = testApplication {
        configureFullApp()
        val (_, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vilkai", "type": "VILKU_DRAUGOVE" }""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `get units returns 200 with list`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        createUnit(token, tuntasId, "Vilkai", "VILKU_DRAUGOVE")
        createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")

        val response = client.get("/api/organizational-units") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(2, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `get units filtered by type returns correct results`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        createUnit(token, tuntasId, "Vilkai", "VILKU_DRAUGOVE")
        createUnit(token, tuntasId, "Gildija", "GILDIJA")

        val response = client.get("/api/organizational-units?type=VILKU_DRAUGOVE") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `get single unit returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)

        val response = client.get("/api/organizational-units/$unitId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Vilkai", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `get nonexistent unit returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/organizational-units/00000000-0000-0000-0000-000000000000") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `update unit name returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)

        val response = client.put("/api/organizational-units/$unitId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vilkai atnaujinta" }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Vilkai atnaujinta", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `delete unit returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)

        val deleteResponse = client.delete("/api/organizational-units/$unitId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        val getResponse = client.get("/api/organizational-units/$unitId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }

    @Test
    fun `delete unit with active items in custody returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)

        // Assign an item to this unit as custodian
        client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Palapine", "category": "COLLECTIVE", "quantity": 1, "custodianId": "$unitId" }""")
        }

        val response = client.delete("/api/organizational-units/$unitId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ── Unit membership ───────────────────────────────────────────────────────

    @Test
    fun `assign member returns 201 with assignmentType MEMBER`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)
        val (_, secondUserId) = registerSecondUser(token, tuntasId, "Skautas")

        val response = client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "MEMBER" }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(secondUserId, body["userId"]?.jsonPrimitive?.content)
        assertEquals(unitId, body["organizationalUnitId"]?.jsonPrimitive?.content)
        assertEquals("MEMBER", body["assignmentType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assign member as VADOVO_PADEJEJAS returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)
        val (_, secondUserId) = registerSecondUser(token, tuntasId, "Skautas")

        val response = client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "VADOVO_PADEJEJAS" }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("VADOVO_PADEJEJAS", body["assignmentType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assign member with nonexistent userId returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)

        val response = client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "00000000-0000-0000-0000-000000000000", "assignmentType": "MEMBER" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `assign member already primary in same type unit returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unit1Id = createUnit(token, tuntasId, "Vilkai 1", "VILKU_DRAUGOVE")
        val unit2Id = createUnit(token, tuntasId, "Vilkai 2", "VILKU_DRAUGOVE")
        val (_, secondUserId) = registerSecondUser(token, tuntasId, "Skautas")

        client.post("/api/organizational-units/$unit1Id/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "MEMBER" }""")
        }

        val response = client.post("/api/organizational-units/$unit2Id/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "MEMBER" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `assign member primary in different type units succeeds`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val vilkuId = createUnit(token, tuntasId, "Vilkai", "VILKU_DRAUGOVE")
        val skautuId = createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")
        val (_, secondUserId) = registerSecondUser(token, tuntasId, "Skautas")

        client.post("/api/organizational-units/$vilkuId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "MEMBER" }""")
        }

        val response = client.post("/api/organizational-units/$skautuId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "MEMBER" }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `get unit members returns 200 with active members only`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)
        val (_, secondUserId) = registerSecondUser(token, tuntasId, "Skautas")

        client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "MEMBER" }""")
        }

        val response = client.get("/api/organizational-units/$unitId/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `remove member from unit returns 200 and sets left_at`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)
        val (_, secondUserId) = registerSecondUser(token, tuntasId, "Skautas")

        client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "MEMBER" }""")
        }

        val response = client.delete("/api/organizational-units/$unitId/members/$secondUserId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Verify left_at is set in unit_assignments
        org.jetbrains.exposed.sql.transactions.transaction {
            exec("SELECT left_at FROM unit_assignments WHERE user_id = '$secondUserId' AND organizational_unit_id = '$unitId'") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
            }
        }

        // Verify member no longer appears in active list
        val getResponse = client.get("/api/organizational-units/$unitId/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val body = Json.parseToJsonElement(getResponse.bodyAsText()).jsonObject
        assertEquals(0, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `remove nonexistent unit member returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)

        val response = client.delete("/api/organizational-units/$unitId/members/00000000-0000-0000-0000-000000000000") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `assign member without permission returns 403`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)
        val (secondToken, secondUserId) = registerSecondUser(token, tuntasId, "Skautas")

        val response = client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $secondToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "MEMBER" }""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
