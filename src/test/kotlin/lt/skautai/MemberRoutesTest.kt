package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberRoutesTest {

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


    @Test
    fun `get members returns 200 with tuntininkas`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `get members without token returns 401`() = testApplication {
        configureFullApp()
        val (_, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/members") {
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `get single member returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        // Get userId from members list
        val listResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        val response = client.get("/api/members/$userId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(userId, body["userId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `get nonexistent member returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/members/00000000-0000-0000-0000-000000000000") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `assign leadership role returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val listResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        val inventorininkaasRoleId = TestHelper.getRoleId(tuntasId, "Inventorininkas")

        val response = client.post("/api/members/$userId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "roleId": "$inventorininkaasRoleId",
                    "startsAt": "2025-01-01T00:00:00Z",
                    "expiresAt": "2026-01-01T00:00:00Z",
                    "termNumber": 1
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Inventorininkas", body["roleName"]?.jsonPrimitive?.content)
        assertEquals("ACTIVE", body["termStatus"]?.jsonPrimitive?.content)
    }

    @Test
    fun `update leadership role term status returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val listResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        val inventorininkaasRoleId = TestHelper.getRoleId(tuntasId, "Inventorininkas")

        val assignResponse = client.post("/api/members/$userId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "roleId": "$inventorininkaasRoleId",
                    "termNumber": 1
                }
            """.trimIndent())
        }

        val assignmentId = Json.parseToJsonElement(assignResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.put("/api/members/$userId/leadership-roles/$assignmentId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "termStatus": "RESIGNED" }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("RESIGNED", body["termStatus"]?.jsonPrimitive?.content)
        assertNotNull(body["leftAt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `remove leadership role returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val listResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        val inventorininkaasRoleId = TestHelper.getRoleId(tuntasId, "Inventorininkas")

        val assignResponse = client.post("/api/members/$userId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$inventorininkaasRoleId", "termNumber": 1 }""")
        }

        val assignmentId = Json.parseToJsonElement(assignResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.delete("/api/members/$userId/leadership-roles/$assignmentId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `assign rank returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val listResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")

        val response = client.post("/api/members/$userId/ranks") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$skautasRoleId" }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Skautas", body["roleName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assign leadership role with wrong role type returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val listResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        // Try to assign a RANK role via leadership role endpoint
        val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")

        val response = client.post("/api/members/$userId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$skautasRoleId", "termNumber": 1 }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `remove rank returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val listResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")

        val assignResponse = client.post("/api/members/$userId/ranks") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$skautasRoleId" }""")
        }

        val rankId = Json.parseToJsonElement(assignResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.delete("/api/members/$userId/ranks/$rankId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }


    @Test
    fun `remove member sets left_at and closes active leadership roles`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        // Register second user via invite
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")
            setBody("""{ "roleId": "$skautasRoleId" }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "name": "Second",
                "surname": "User",
                "email": "second@test.com",
                "password": "test123",
                "inviteCode": "$inviteCode"
            }
        """.trimIndent())
        }
        val secondUserId = Json.parseToJsonElement(registerResponse.bodyAsText())
            .jsonObject["userId"]!!.jsonPrimitive.content

        // Assign a leadership role to second user
        val inventorininkaasRoleId = TestHelper.getRoleId(tuntasId, "Inventorininkas")
        client.post("/api/members/$secondUserId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$inventorininkaasRoleId", "termNumber": 1 }""")
        }

        // Remove the member
        val response = client.delete("/api/members/$secondUserId/remove") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Verify membership left_at is set
        transaction {
            exec("SELECT left_at FROM user_tuntas_memberships WHERE user_id = '$secondUserId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
            }
        }

        // Verify active leadership roles are closed
        transaction {
            exec("SELECT term_status, left_at FROM user_leadership_roles WHERE user_id = '$secondUserId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertEquals("RESIGNED", rs.getString("term_status"))
                assertNotNull(rs.getTimestamp("left_at"))
            }
        }
    }

    @Test
    fun `remove member without permission returns 403`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        // Register second user (Skautas - no members.remove permission)
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")
            setBody("""{ "roleId": "$skautasRoleId" }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val secondTokenResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "name": "Second",
                "surname": "User",
                "email": "second@test.com",
                "password": "test123",
                "inviteCode": "$inviteCode"
            }
        """.trimIndent())
        }
        val secondToken = Json.parseToJsonElement(secondTokenResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
        val secondUserId = Json.parseToJsonElement(secondTokenResponse.bodyAsText())
            .jsonObject["userId"]!!.jsonPrimitive.content

        // Second user (Skautas) tries to remove the tuntininkas
        val tuntininkaasId = transaction {
            var id = ""
            exec("SELECT user_id FROM user_tuntas_memberships WHERE tuntas_id = '$tuntasId' AND user_id != '$secondUserId' LIMIT 1") { rs ->
                if (rs.next()) id = rs.getString("user_id")
            }
            id
        }

        val response = client.delete("/api/members/$tuntininkaasId/remove") {
            header("Authorization", "Bearer $secondToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `remove nonexistent member returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.delete("/api/members/00000000-0000-0000-0000-000000000000/remove") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `resign removes self from tuntas and closes active leadership roles`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        // Register second user
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            val draugininkaasRoleId = TestHelper.getRoleId(tuntasId, "Draugininkas")
            setBody("""{ "roleId": "$draugininkaasRoleId" }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val secondResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "name": "Second",
                "surname": "User",
                "email": "second@test.com",
                "password": "test123",
                "inviteCode": "$inviteCode"
            }
        """.trimIndent())
        }
        val secondToken = Json.parseToJsonElement(secondResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
        val secondUserId = Json.parseToJsonElement(secondResponse.bodyAsText())
            .jsonObject["userId"]!!.jsonPrimitive.content

        // Assign leadership role to second user so we can verify it gets closed
        val inventorininkaasRoleId = TestHelper.getRoleId(tuntasId, "Inventorininkas")
        client.post("/api/members/$secondUserId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$inventorininkaasRoleId", "termNumber": 1 }""")
        }

        // Second user resigns
        val response = client.post("/api/members/$secondUserId/resign") {
            header("Authorization", "Bearer $secondToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        transaction {
            exec("SELECT left_at FROM user_tuntas_memberships WHERE user_id = '$secondUserId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
            }
        }

        transaction {
            exec("SELECT term_status, left_at FROM user_leadership_roles WHERE user_id = '$secondUserId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertEquals("RESIGNED", rs.getString("term_status"))
                assertNotNull(rs.getTimestamp("left_at"))
            }
        }
    }

    @Test
    fun `resign after already being removed returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        // Register second user
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")
            setBody("""{ "roleId": "$skautasRoleId" }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val secondResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "name": "Second",
                "surname": "User",
                "email": "second@test.com",
                "password": "test123",
                "inviteCode": "$inviteCode"
            }
        """.trimIndent())
        }
        val secondToken = Json.parseToJsonElement(secondResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
        val secondUserId = Json.parseToJsonElement(secondResponse.bodyAsText())
            .jsonObject["userId"]!!.jsonPrimitive.content

        // Admin removes the second user first
        client.delete("/api/members/$secondUserId/remove") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        // Second user now tries to resign — should fail as they are no longer active
        val response = client.post("/api/members/$secondUserId/resign") {
            header("Authorization", "Bearer $secondToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }



}
