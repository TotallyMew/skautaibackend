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


    private suspend fun ApplicationTestBuilder.createUnit(
        token: String,
        tuntasId: String,
        name: String = "Vilkai",
        type: String = "VILKU_DRAUGOVE",
        subType: String? = null
    ): String {
        val subTypeField = subType?.let { """, "subType": "$it"""" }.orEmpty()
        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "type": "$type"$subTypeField }""")
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.registerSecondUser(
        token: String,
        tuntasId: String,
        roleName: String,
        email: String = "second@test.com",
        organizationalUnitId: String? = null
    ): Pair<String, String> {
        val roleId = TestHelper.getRoleId(tuntasId, roleName)
        val unitField = organizationalUnitId?.let { ", \"organizationalUnitId\": \"$it\"" }.orEmpty()
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId"$unitField, "expiresInHours": 48 }""")
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
                    "password": "testas123",
                    "inviteCode": "$inviteCode"
                }
            """.trimIndent())
        }
        val body = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
        return body["token"]!!.jsonPrimitive.content to body["userId"]!!.jsonPrimitive.content
    }


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
    fun `former deputy with vadovas rank sees all units and members after step down`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")
        val otherUnitId = createUnit(token, tuntasId, "Vilkai", "VILKU_DRAUGOVE")
        val (leaderToken, leaderUserId) = registerSecondUser(
            token = token,
            tuntasId = tuntasId,
            roleName = "Draugininko pavaduotojas",
            email = "former-leader@test.com",
            organizationalUnitId = unitId
        )
        val (_, memberId) = registerSecondUser(token, tuntasId, "Skautas", "member@test.com")

        client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$memberId", "assignmentType": "MEMBER" }""")
        }

        val leaderDetail = client.get("/api/members/$leaderUserId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val assignmentId = Json.parseToJsonElement(leaderDetail.bodyAsText())
            .jsonObject["leadershipRoles"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val stepDown = client.post("/api/members/me/leadership-roles/$assignmentId/step-down") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, stepDown.status)

        val unitsResponse = client.get("/api/organizational-units") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, unitsResponse.status)
        val unitsBody = Json.parseToJsonElement(unitsResponse.bodyAsText()).jsonObject
        assertEquals(2, unitsBody["total"]?.jsonPrimitive?.content?.toInt())

        val ownUnitMembersResponse = client.get("/api/organizational-units/$unitId/members") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, ownUnitMembersResponse.status)

        val otherUnitMembersResponse = client.get("/api/organizational-units/$otherUnitId/members") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, otherUnitMembersResponse.status)
    }

    @Test
    fun `vadovas can read all units and unit members but cannot edit them`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val ownUnitId = createUnit(token, tuntasId, "Gildija", "GILDIJA")
        val otherUnitId = createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")
        val (vadovasToken, _) = registerSecondUser(
            token = token,
            tuntasId = tuntasId,
            roleName = "Vadovas",
            email = "vadovas-units@test.com",
            organizationalUnitId = ownUnitId
        )
        val (_, skautasUserId) = registerSecondUser(
            token = token,
            tuntasId = tuntasId,
            roleName = "Skautas",
            email = "skautas-other-unit@test.com",
            organizationalUnitId = otherUnitId
        )

        val unitsResponse = client.get("/api/organizational-units") {
            header("Authorization", "Bearer $vadovasToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, unitsResponse.status)
        val unitsBody = Json.parseToJsonElement(unitsResponse.bodyAsText()).jsonObject
        assertEquals(2, unitsBody["total"]?.jsonPrimitive?.content?.toInt())

        val otherUnitResponse = client.get("/api/organizational-units/$otherUnitId") {
            header("Authorization", "Bearer $vadovasToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, otherUnitResponse.status)

        val otherUnitMembersResponse = client.get("/api/organizational-units/$otherUnitId/members") {
            header("Authorization", "Bearer $vadovasToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, otherUnitMembersResponse.status)

        val updateUnitResponse = client.put("/api/organizational-units/$otherUnitId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $vadovasToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Pakeistas pavadinimas" }""")
        }
        assertEquals(HttpStatusCode.Forbidden, updateUnitResponse.status)

        val addMemberResponse = client.post("/api/organizational-units/$ownUnitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $vadovasToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$skautasUserId", "assignmentType": "MEMBER" }""")
        }
        assertEquals(HttpStatusCode.Forbidden, addMemberResponse.status)
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
            setBody("""{ "name": "Palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1, "custodianId": "$unitId" }""")
        }

        val response = client.delete("/api/organizational-units/$unitId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }


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
    fun `unit leader can remove member from own unit`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")
        val (leaderToken, _) = registerSecondUser(
            token = token,
            tuntasId = tuntasId,
            roleName = "Draugininkas",
            email = "leader@test.com",
            organizationalUnitId = unitId
        )
        val (_, memberId) = registerSecondUser(token, tuntasId, "Skautas", "member@test.com")

        client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$memberId", "assignmentType": "MEMBER" }""")
        }

        val response = client.delete("/api/organizational-units/$unitId/members/$memberId") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `unit leader cannot remove member from another unit`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val ownUnitId = createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")
        val otherUnitId = createUnit(token, tuntasId, "Vilkai", "VILKU_DRAUGOVE")
        val (leaderToken, _) = registerSecondUser(
            token = token,
            tuntasId = tuntasId,
            roleName = "Draugininkas",
            email = "leader@test.com",
            organizationalUnitId = ownUnitId
        )
        val (_, memberId) = registerSecondUser(token, tuntasId, "Skautas", "member@test.com")

        client.post("/api/organizational-units/$otherUnitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$memberId", "assignmentType": "MEMBER" }""")
        }

        val response = client.delete("/api/organizational-units/$otherUnitId/members/$memberId") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `leave unit closes own assignment but blocks active leadership`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")
        val (secondToken, secondUserId) = registerSecondUser(token, tuntasId, "Skautas")

        client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "MEMBER" }""")
        }

        val draugininkasRoleId = TestHelper.getRoleId(tuntasId, "Draugininkas")
        client.post("/api/members/$secondUserId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$draugininkasRoleId", "organizationalUnitId": "$unitId" }""")
        }

        val blockedLeave = client.post("/api/organizational-units/$unitId/members/me/leave") {
            header("Authorization", "Bearer $secondToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, blockedLeave.status)

        org.jetbrains.exposed.sql.transactions.transaction {
            exec("""
                UPDATE user_leadership_roles
                SET term_status = 'RESIGNED', left_at = CURRENT_TIMESTAMP
                WHERE user_id = '$secondUserId' AND tuntas_id = '$tuntasId'
            """.trimIndent())
        }

        val leave = client.post("/api/organizational-units/$unitId/members/me/leave") {
            header("Authorization", "Bearer $secondToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, leave.status)

        org.jetbrains.exposed.sql.transactions.transaction {
            exec("SELECT left_at FROM unit_assignments WHERE user_id = '$secondUserId' AND organizational_unit_id = '$unitId'") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
            }
            exec("SELECT left_at FROM user_tuntas_memberships WHERE user_id = '$secondUserId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertNull(rs.getTimestamp("left_at"))
            }
        }
    }

    @Test
    fun `leave unit closes only selected unit and keeps other assignments`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val skautuId = createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")
        val gildijaId = createUnit(token, tuntasId, "Gildija", "GILDIJA")
        val (memberToken, memberId) = registerSecondUser(token, tuntasId, "Skautas", "multi-unit@test.com")

        client.post("/api/organizational-units/$skautuId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$memberId", "assignmentType": "MEMBER" }""")
        }
        client.post("/api/organizational-units/$gildijaId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$memberId", "assignmentType": "MEMBER" }""")
        }

        val leave = client.post("/api/organizational-units/$skautuId/members/me/leave") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, leave.status)

        org.jetbrains.exposed.sql.transactions.transaction {
            exec("""
                SELECT
                    SUM(CASE WHEN organizational_unit_id = '$skautuId' AND left_at IS NOT NULL THEN 1 ELSE 0 END) AS closed_selected,
                    SUM(CASE WHEN organizational_unit_id = '$gildijaId' AND left_at IS NULL THEN 1 ELSE 0 END) AS kept_other
                FROM unit_assignments
                WHERE user_id = '$memberId'
            """.trimIndent()) { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("closed_selected"))
                assertEquals(1, rs.getInt("kept_other"))
            }
            exec("SELECT left_at FROM user_tuntas_memberships WHERE user_id = '$memberId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertNull(rs.getTimestamp("left_at"))
            }
        }
    }

    @Test
    fun `member can leave last unit and stays tuntas member`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")
        val (memberToken, memberId) = registerSecondUser(token, tuntasId, "Skautas", "last-unit@test.com")

        client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$memberId", "assignmentType": "MEMBER" }""")
        }

        val leave = client.post("/api/organizational-units/$unitId/members/me/leave") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, leave.status)

        org.jetbrains.exposed.sql.transactions.transaction {
            exec("SELECT left_at FROM unit_assignments WHERE user_id = '$memberId' AND organizational_unit_id = '$unitId'") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
            }
            exec("SELECT left_at FROM user_tuntas_memberships WHERE user_id = '$memberId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertNull(rs.getTimestamp("left_at"))
            }
        }
    }

    @Test
    fun `remove member from unit closes leadership roles for that unit`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")
        val (_, secondUserId) = registerSecondUser(token, tuntasId, "Skautas")

        client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "MEMBER" }""")
        }
        val draugininkasRoleId = TestHelper.getRoleId(tuntasId, "Draugininkas")
        client.post("/api/members/$secondUserId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$draugininkasRoleId", "organizationalUnitId": "$unitId" }""")
        }

        val response = client.delete("/api/organizational-units/$unitId/members/$secondUserId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, response.status)

        org.jetbrains.exposed.sql.transactions.transaction {
            exec("SELECT term_status, left_at FROM user_leadership_roles WHERE user_id = '$secondUserId' AND organizational_unit_id = '$unitId'") { rs ->
                assertTrue(rs.next())
                assertEquals("RESIGNED", rs.getString("term_status"))
                assertNotNull(rs.getTimestamp("left_at"))
            }
        }
    }

    @Test
    fun `unit leader can remove deputy and removed vadovas keeps read visibility`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")
        val (leaderToken, _) = registerSecondUser(
            token = token,
            tuntasId = tuntasId,
            roleName = "Draugininkas",
            email = "kick-leader@test.com",
            organizationalUnitId = unitId
        )
        val (deputyToken, deputyId) = registerSecondUser(
            token = token,
            tuntasId = tuntasId,
            roleName = "Draugininko pavaduotojas",
            email = "kick-deputy@test.com",
            organizationalUnitId = unitId
        )

        val response = client.delete("/api/organizational-units/$unitId/members/$deputyId") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val removedUnitMembers = client.get("/api/organizational-units/$unitId/members") {
            header("Authorization", "Bearer $deputyToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, removedUnitMembers.status)

        org.jetbrains.exposed.sql.transactions.transaction {
            exec("SELECT term_status, left_at FROM user_leadership_roles WHERE user_id = '$deputyId' AND organizational_unit_id = '$unitId'") { rs ->
                assertTrue(rs.next())
                assertEquals("RESIGNED", rs.getString("term_status"))
                assertNotNull(rs.getTimestamp("left_at"))
            }
            exec("SELECT left_at FROM unit_assignments WHERE user_id = '$deputyId' AND organizational_unit_id = '$unitId'") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
            }
            exec("SELECT left_at FROM user_tuntas_memberships WHERE user_id = '$deputyId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertNull(rs.getTimestamp("left_at"))
            }
        }
    }

    @Test
    fun `move member closes previous primary membership but keeps helper assignment`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unit1Id = createUnit(token, tuntasId, "Skautai 1", "SKAUTU_DRAUGOVE")
        val unit2Id = createUnit(token, tuntasId, "Gildija", "GILDIJA")
        val helperUnitId = createUnit(token, tuntasId, "Vilkai", "VILKU_DRAUGOVE")
        val (_, secondUserId) = registerSecondUser(token, tuntasId, "Skautas")

        client.post("/api/organizational-units/$unit1Id/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "MEMBER" }""")
        }
        client.post("/api/organizational-units/$helperUnitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "VADOVO_PADEJEJAS" }""")
        }

        val response = client.post("/api/organizational-units/$unit2Id/members/$secondUserId/move") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(unit2Id, body["organizationalUnitId"]?.jsonPrimitive?.content)
        assertEquals("MEMBER", body["assignmentType"]?.jsonPrimitive?.content)

        org.jetbrains.exposed.sql.transactions.transaction {
            exec("""
                SELECT
                    SUM(CASE WHEN organizational_unit_id = '$unit1Id' AND assignment_type = 'MEMBER' AND left_at IS NOT NULL THEN 1 ELSE 0 END) AS closed_old,
                    SUM(CASE WHEN organizational_unit_id = '$unit2Id' AND assignment_type = 'MEMBER' AND left_at IS NULL THEN 1 ELSE 0 END) AS active_new,
                    SUM(CASE WHEN organizational_unit_id = '$helperUnitId' AND assignment_type = 'VADOVO_PADEJEJAS' AND left_at IS NULL THEN 1 ELSE 0 END) AS active_helper
                FROM unit_assignments
                WHERE user_id = '$secondUserId'
            """.trimIndent()) { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("closed_old"))
                assertEquals(1, rs.getInt("active_new"))
                assertEquals(1, rs.getInt("active_helper"))
            }
        }

        val memberResponse = client.get("/api/members/$secondUserId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val memberBody = Json.parseToJsonElement(memberResponse.bodyAsText()).jsonObject
        val activeUnitIds = memberBody["unitAssignments"]!!.jsonArray
            .map { it.jsonObject["organizationalUnitId"]!!.jsonPrimitive.content }
            .toSet()
        assertFalse(unit1Id in activeUnitIds)
        assertTrue(unit2Id in activeUnitIds)
        assertTrue(helperUnitId in activeUnitIds)
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

    @Test
    fun `senior unit leader can inspect member list privacy audit`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Vyr. skautai", "VYR_SKAUTU_VIENETAS", "DRAUGOVE")
        val (leaderToken, leaderUserId) = registerSecondUser(
            token = token,
            tuntasId = tuntasId,
            roleName = "Vyr. skautu draugoves draugininkas",
            email = "senior-audit-leader@test.com",
            organizationalUnitId = unitId
        )

        val membersResponse = client.get("/api/organizational-units/$unitId/members") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, membersResponse.status, membersResponse.bodyAsText())

        val auditResponse = client.get("/api/organizational-units/$unitId/privacy-audit") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, auditResponse.status, auditResponse.bodyAsText())
        val entries = Json.parseToJsonElement(auditResponse.bodyAsText()).jsonObject["entries"]!!.jsonArray
        assertTrue(entries.isNotEmpty())
        val latest = entries.first().jsonObject
        assertEquals(leaderUserId, latest["actorUserId"]!!.jsonPrimitive.content)
        assertEquals("VIEW_MEMBER_LIST", latest["action"]!!.jsonPrimitive.content)
        assertEquals("INTERNAL", latest["accessMode"]!!.jsonPrimitive.content)
    }
}
