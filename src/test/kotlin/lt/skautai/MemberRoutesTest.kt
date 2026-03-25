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

    // Helper to get role ID by name for a tuntas
    private fun getRoleId(tuntasId: String, roleName: String): String {
        var id = ""
        transaction {
            exec("SELECT id FROM roles WHERE tuntas_id = '$tuntasId' AND name = '$roleName' LIMIT 1") { rs ->
                if (rs.next()) id = rs.getString("id")
            }
        }
        return id
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

        val inventorininkaasRoleId = getRoleId(tuntasId, "Inventorininkas")

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

        val inventorininkaasRoleId = getRoleId(tuntasId, "Inventorininkas")

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

        val inventorininkaasRoleId = getRoleId(tuntasId, "Inventorininkas")

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

        val skautasRoleId = getRoleId(tuntasId, "Skautas")

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
        val skautasRoleId = getRoleId(tuntasId, "Skautas")

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

        val skautasRoleId = getRoleId(tuntasId, "Skautas")

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
}