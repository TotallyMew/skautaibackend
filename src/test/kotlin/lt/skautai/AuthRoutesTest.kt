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
class AuthRoutesTest {

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
    fun `register tuntininkas returns 201 with token`() = testApplication {
        configureFullApp()

        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test",
                    "surname": "User",
                    "email": "test@test.com",
                    "password": "test123",
                    "tuntasName": "Test Tuntas",
                    "tuntasKrastas": "Vilnius"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["token"])
        assertEquals("test@test.com", body["email"]?.jsonPrimitive?.content)
    }

    @Test
    fun `register duplicate email returns 400`() = testApplication {
        configureFullApp()

        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test",
                    "surname": "User",
                    "email": "duplicate@test.com",
                    "password": "test123",
                    "tuntasName": "Test Tuntas"
                }
            """.trimIndent())
        }

        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test2",
                    "surname": "User2",
                    "email": "duplicate@test.com",
                    "password": "test123",
                    "tuntasName": "Another Tuntas"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Email already registered", body["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `login with valid credentials returns 200`() = testApplication {
        configureFullApp()

        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test",
                    "surname": "User",
                    "email": "login@test.com",
                    "password": "test123",
                    "tuntasName": "Test Tuntas"
                }
            """.trimIndent())
        }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "email": "login@test.com",
                    "password": "test123"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["token"])
    }

    @Test
    fun `login with wrong password returns 401`() = testApplication {
        configureFullApp()

        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test",
                    "surname": "User",
                    "email": "wrong@test.com",
                    "password": "test123",
                    "tuntasName": "Test Tuntas"
                }
            """.trimIndent())
        }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "email": "wrong@test.com",
                    "password": "wrongpassword"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `login with nonexistent email returns 401`() = testApplication {
        configureFullApp()

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "email": "nonexistent@test.com",
                    "password": "test123"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `seed super admin works when none exists`() = testApplication {
        configureFullApp()

        val response = client.post("/api/setup/super-admin") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "email": "admin@test.com",
                    "password": "admin123"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `seed super admin fails when one already exists`() = testApplication {
        configureFullApp()

        client.post("/api/setup/super-admin") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "email": "admin@test.com",
                    "password": "admin123"
                }
            """.trimIndent())
        }

        val response = client.post("/api/setup/super-admin") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "email": "admin2@test.com",
                    "password": "admin123"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `create invitation returns 201 with code`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val roleId = getRoleId(tuntasId, "Draugininkas")

        val response = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "roleId": "$roleId",
                    "expiresInHours": 48
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["code"]?.jsonPrimitive?.content)
        assertEquals("Draugininkas", body["roleName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create invitation without token returns 401`() = testApplication {
        configureFullApp()
        val (_, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "00000000-0000-0000-0000-000000000000", "expiresInHours": 48 }""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `create invitation without tuntas header returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val roleId = getRoleId(tuntasId, "Draugininkas")

        val response = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{ "roleId": "$roleId", "expiresInHours": 48 }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `create invitation with nonexistent role returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "00000000-0000-0000-0000-000000000000", "expiresInHours": 48 }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `create invitation for pending tuntas returns 400`() = testApplication {
        configureFullApp()

        val registerResponse = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test",
                    "surname": "User",
                    "email": "pending@test.com",
                    "password": "test123",
                    "tuntasName": "Pending Tuntas"
                }
            """.trimIndent())
        }
        val body = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
        val token = body["token"]!!.jsonPrimitive.content

        val tuntasId = transaction {
            var id = ""
            exec("SELECT id FROM tuntai WHERE name = 'Pending Tuntas' LIMIT 1") { rs ->
                if (rs.next()) id = rs.getString("id")
            }
            id
        }

        val roleId = getRoleId(tuntasId, "Draugininkas")

        val response = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId", "expiresInHours": 48 }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `register with valid invite code returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val roleId = getRoleId(tuntasId, "Draugininkas")

        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId", "expiresInHours": 48 }""")
        }
        val code = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val response = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Jonas",
                    "surname": "Jonaitis",
                    "email": "jonas@test.com",
                    "password": "test123",
                    "inviteCode": "$code"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val responseBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(responseBody["token"]?.jsonPrimitive?.content)
        assertEquals("jonas@test.com", responseBody["email"]?.jsonPrimitive?.content)
    }

    @Test
    fun `register with invalid invite code returns 400`() = testApplication {
        configureFullApp()

        val response = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Jonas",
                    "surname": "Jonaitis",
                    "email": "jonas@test.com",
                    "password": "test123",
                    "inviteCode": "INVALIDCODE"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `register with already used invite code returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val roleId = getRoleId(tuntasId, "Draugininkas")

        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId", "expiresInHours": 48 }""")
        }
        val code = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Jonas",
                    "surname": "Jonaitis",
                    "email": "jonas@test.com",
                    "password": "test123",
                    "inviteCode": "$code"
                }
            """.trimIndent())
        }

        val response = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Petras",
                    "surname": "Petraitis",
                    "email": "petras@test.com",
                    "password": "test123",
                    "inviteCode": "$code"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `register with duplicate email via invite returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val roleId = getRoleId(tuntasId, "Draugininkas")

        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId", "expiresInHours": 48 }""")
        }
        val code = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val response = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test",
                    "surname": "User",
                    "email": "tuntininkas@test.com",
                    "password": "test123",
                    "inviteCode": "$code"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `registered user via invite gets correct role assigned`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val roleId = getRoleId(tuntasId, "Draugininkas")

        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId", "expiresInHours": 48 }""")
        }
        val code = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Jonas",
                    "surname": "Jonaitis",
                    "email": "jonas@test.com",
                    "password": "test123",
                    "inviteCode": "$code"
                }
            """.trimIndent())
        }

        val newToken = Json.parseToJsonElement(registerResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content

        val membersResponse = client.get("/api/members") {
            header("Authorization", "Bearer $newToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, membersResponse.status)
        val body = Json.parseToJsonElement(membersResponse.bodyAsText()).jsonObject
        assertEquals(2, body["total"]?.jsonPrimitive?.content?.toInt())
    }


    @Test
    fun `super admin login with valid credentials returns 200`() = testApplication {
        configureFullApp()

        // Seed super admin first
        client.post("/api/setup/super-admin") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }

        val response = client.post("/api/super-admin/login") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["token"]?.jsonPrimitive?.content)
        assertEquals("super_admin", body["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `super admin login with wrong password returns 401`() = testApplication {
        configureFullApp()

        client.post("/api/setup/super-admin") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }

        val response = client.post("/api/super-admin/login") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "wrongpassword" }""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `super admin can list tuntai`() = testApplication {
        configureFullApp()

        // Register a tuntas
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "name": "Test",
                "surname": "User",
                "email": "tuntininkas@test.com",
                "password": "test123",
                "tuntasName": "Test Tuntas"
            }
        """.trimIndent())
        }

        // Seed and login super admin
        client.post("/api/setup/super-admin") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }

        val loginResponse = client.post("/api/super-admin/login") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }
        val adminToken = Json.parseToJsonElement(loginResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content

        val response = client.get("/api/super-admin/tuntai") {
            header("Authorization", "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(1, body.size)
        assertEquals("Test Tuntas", body[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `super admin can approve pending tuntas`() = testApplication {
        configureFullApp()

        // Register a tuntas
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "name": "Test",
                "surname": "User",
                "email": "tuntininkas@test.com",
                "password": "test123",
                "tuntasName": "Test Tuntas"
            }
        """.trimIndent())
        }

        val tuntasId = transaction {
            var id = ""
            exec("SELECT id FROM tuntai WHERE name = 'Test Tuntas' LIMIT 1") { rs ->
                if (rs.next()) id = rs.getString("id")
            }
            id
        }

        // Seed and login super admin
        client.post("/api/setup/super-admin") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }

        val loginResponse = client.post("/api/super-admin/login") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }
        val adminToken = Json.parseToJsonElement(loginResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content

        val response = client.post("/api/super-admin/tuntai/$tuntasId/approve") {
            header("Authorization", "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Verify tuntas is now active
        val tuntaiResponse = client.get("/api/super-admin/tuntai") {
            header("Authorization", "Bearer $adminToken")
        }
        val tuntai = Json.parseToJsonElement(tuntaiResponse.bodyAsText()).jsonArray
        assertEquals("ACTIVE", tuntai[0].jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `super admin approve already active tuntas returns 400`() = testApplication {
        configureFullApp()

        val (_, tuntasId) = client.registerAndActivateTuntininkas()

        client.post("/api/setup/super-admin") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }

        val loginResponse = client.post("/api/super-admin/login") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }
        val adminToken = Json.parseToJsonElement(loginResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content

        val response = client.post("/api/super-admin/tuntai/$tuntasId/approve") {
            header("Authorization", "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `list tuntai without super admin token returns 401`() = testApplication {
        configureFullApp()

        val response = client.get("/api/super-admin/tuntai")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `super admin can reject pending tuntas`() = testApplication {
        configureFullApp()

        // Register a tuntas
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
        {
            "name": "Test",
            "surname": "User",
            "email": "tuntininkas2@test.com",
            "password": "test123",
            "tuntasName": "Test Tuntas Reject"
        }
    """.trimIndent())
        }

        val tuntasId = transaction {
            var id = ""
            exec("SELECT id FROM tuntai WHERE name = 'Test Tuntas Reject' LIMIT 1") { rs ->
                if (rs.next()) id = rs.getString("id")
            }
            id
        }

        // Seed and login super admin
        client.post("/api/setup/super-admin") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }

        val loginResponse = client.post("/api/super-admin/login") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }
        val adminToken = Json.parseToJsonElement(loginResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content

        val response = client.post("/api/super-admin/tuntai/$tuntasId/reject") {
            header("Authorization", "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Verify tuntas is now rejected
        val tuntaiResponse = client.get("/api/super-admin/tuntai") {
            header("Authorization", "Bearer $adminToken")
        }
        val tuntai = Json.parseToJsonElement(tuntaiResponse.bodyAsText()).jsonArray
        val rejectedTuntas = tuntai.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.content == "Test Tuntas Reject"
        }
        assertNotNull(rejectedTuntas)
        assertEquals("REJECTED", rejectedTuntas!!.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `super admin cannot reject already active tuntas`() = testApplication {
        configureFullApp()

        // Register a tuntas
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
        {
            "name": "Test",
            "surname": "User",
            "email": "tuntininkas3@test.com",
            "password": "test123",
            "tuntasName": "Test Tuntas Active"
        }
    """.trimIndent())
        }

        val tuntasId = transaction {
            var id = ""
            exec("SELECT id FROM tuntai WHERE name = 'Test Tuntas Active' LIMIT 1") { rs ->
                if (rs.next()) id = rs.getString("id")
            }
            id
        }

        // Seed and login super admin
        client.post("/api/setup/super-admin") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }

        val loginResponse = client.post("/api/super-admin/login") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }
        val adminToken = Json.parseToJsonElement(loginResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content

        // First approve it
        client.post("/api/super-admin/tuntai/$tuntasId/approve") {
            header("Authorization", "Bearer $adminToken")
        }

        // Now try to reject it
        val response = client.post("/api/super-admin/tuntai/$tuntasId/reject") {
            header("Authorization", "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

}