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

    private suspend fun ApplicationTestBuilder.createUnit(
        token: String,
        tuntasId: String,
        name: String,
        type: String
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
                    "password": "testas123",
                    "tuntasName": "Test Tuntas",
                    "tuntasKrastas": "Vilniaus"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["token"])
        assertEquals("test@test.com", body["email"]?.jsonPrimitive?.content)
    }

    @Test
    fun `register stores creator email as tuntas contact email`() = testApplication {
        configureFullApp()

        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test",
                    "surname": "User",
                    "email": "Creator@TEST.com",
                    "password": "testas123",
                    "tuntasName": "Contact Tuntas",
                    "tuntasKrastas": "Vilniaus",
                    "tuntasContactEmail": "old-contact@test.com"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("creator@test.com", body["email"]?.jsonPrimitive?.content)

        transaction {
            exec("SELECT contact_email FROM tuntai WHERE name = 'Contact Tuntas' LIMIT 1") { rs ->
                assertTrue(rs.next())
                assertEquals("creator@test.com", rs.getString("contact_email"))
            }
        }
    }

    @Test
    fun `register rejects invalid email`() = testApplication {
        configureFullApp()

        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test",
                    "surname": "User",
                    "email": "not-an-email",
                    "password": "testas123",
                    "tuntasName": "Test Tuntas",
                    "tuntasKrastas": "Vilniaus"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Invalid email format", body["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `register rejects weak password`() = testApplication {
        configureFullApp()

        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test",
                    "surname": "User",
                    "email": "weak-password@test.com",
                    "password": "test123",
                    "tuntasName": "Test Tuntas",
                    "tuntasKrastas": "Vilniaus"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Password must be at least 8 characters", body["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `register requires valid krastas`() = testApplication {
        configureFullApp()

        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test",
                    "surname": "User",
                    "email": "invalid-krastas@test.com",
                    "password": "testas123",
                    "tuntasName": "Test Tuntas",
                    "tuntasKrastas": "Vilnius"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Invalid krastas", body["error"]?.jsonPrimitive?.content)
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
                    "password": "testas123",
                    "tuntasName": "Test Tuntas",
                    "tuntasKrastas": "Vilniaus"
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
                    "password": "testas123",
                    "tuntasName": "Another Tuntas",
                    "tuntasKrastas": "Vilniaus"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Email already registered", body["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `register duplicate tuntas name returns 400 and does not create user`() = testApplication {
        configureFullApp()

        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test",
                    "surname": "User",
                    "email": "first-tuntas@test.com",
                    "password": "testas123",
                    "tuntasName": "Unique Tuntas",
                    "tuntasKrastas": "Vilniaus"
                }
            """.trimIndent())
        }

        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test2",
                    "surname": "User2",
                    "email": "second-tuntas@test.com",
                    "password": "testas123",
                    "tuntasName": "unique tuntas",
                    "tuntasKrastas": "Vilniaus"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Tuntas name already exists", body["error"]?.jsonPrimitive?.content)

        transaction {
            exec("SELECT COUNT(*) AS count FROM users WHERE email = 'second-tuntas@test.com'") { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt("count"))
            }
        }
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
                    "password": "testas123",
                    "tuntasName": "Test Tuntas",
                    "tuntasKrastas": "Vilniaus"
                }
            """.trimIndent())
        }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "email": "login@test.com",
                    "password": "testas123"
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
                    "password": "testas123",
                    "tuntasName": "Test Tuntas",
                    "tuntasKrastas": "Vilniaus"
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
                    "password": "testas123"
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
                    "password": "testas123",
                    "tuntasName": "Pending Tuntas",
                    "tuntasKrastas": "Vilniaus"
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
    fun `my tuntai includes pending tuntas and updates to active after approval`() = testApplication {
        configureFullApp()

        val registerResponse = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Pending",
                    "surname": "Owner",
                    "email": "pending-status@test.com",
                    "password": "testas123",
                    "tuntasName": "Pending Status Tuntas",
                    "tuntasKrastas": "Vilniaus"
                }
            """.trimIndent())
        }
        val token = Json.parseToJsonElement(registerResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content

        val pendingResponse = client.get("/api/users/me/tuntai") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, pendingResponse.status)
        val pendingTuntas = Json.parseToJsonElement(pendingResponse.bodyAsText()).jsonArray[0].jsonObject
        assertEquals("PENDING", pendingTuntas["status"]!!.jsonPrimitive.content)
        val tuntasId = pendingTuntas["id"]!!.jsonPrimitive.content

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

        val approveResponse = client.post("/api/super-admin/tuntai/$tuntasId/approve") {
            header("Authorization", "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, approveResponse.status)

        val activeResponse = client.get("/api/users/me/tuntai") {
            header("Authorization", "Bearer $token")
        }
        val activeTuntas = Json.parseToJsonElement(activeResponse.bodyAsText()).jsonArray[0].jsonObject
        assertEquals("ACTIVE", activeTuntas["status"]!!.jsonPrimitive.content)
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
                    "password": "testas123",
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
                    "password": "testas123",
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
                    "password": "testas123",
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
                    "password": "testas123",
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
                    "password": "testas123",
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
                    "password": "testas123",
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
    fun `create invitation for occupied principal unit leader returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")
        val roleId = getRoleId(tuntasId, "Draugininkas")

        val firstInvite = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId", "organizationalUnitId": "$unitId", "expiresInHours": 48 }""")
        }
        val firstCode = Json.parseToJsonElement(firstInvite.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Pirmas",
                    "surname": "Vadovas",
                    "email": "first-leader@test.com",
                    "password": "testas123",
                    "inviteCode": "$firstCode"
                }
            """.trimIndent())
        }

        val response = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId", "organizationalUnitId": "$unitId", "expiresInHours": 48 }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `register with principal unit leader invite fails when slot becomes occupied`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")
        val roleId = getRoleId(tuntasId, "Draugininkas")

        fun invitationBody() = """{ "roleId": "$roleId", "organizationalUnitId": "$unitId", "expiresInHours": 48 }"""

        val firstCode = Json.parseToJsonElement(client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(invitationBody())
        }.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

        val secondCode = Json.parseToJsonElement(client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(invitationBody())
        }.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

        client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Pirmas",
                    "surname": "Vadovas",
                    "email": "first-leader@test.com",
                    "password": "testas123",
                    "inviteCode": "$firstCode"
                }
            """.trimIndent())
        }

        val response = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Antras",
                    "surname": "Vadovas",
                    "email": "second-leader@test.com",
                    "password": "testas123",
                    "inviteCode": "$secondCode"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `existing tuntas member can accept unit invitation`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val skautasRoleId = getRoleId(tuntasId, "Skautas")
        val guildUnitId = createUnit(token, tuntasId, "Gildija", "GILDIJA")

        val firstInvite = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$skautasRoleId", "expiresInHours": 48 }""")
        }
        val firstCode = Json.parseToJsonElement(firstInvite.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Esamas",
                    "surname": "Narys",
                    "email": "existing@test.com",
                    "password": "testas123",
                    "inviteCode": "$firstCode"
                }
            """.trimIndent())
        }
        val existingToken = Json.parseToJsonElement(registerResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
        val existingUserId = Json.parseToJsonElement(registerResponse.bodyAsText())
            .jsonObject["userId"]!!.jsonPrimitive.content

        val vadovasRoleId = getRoleId(tuntasId, "Vadovas")
        val guildInvite = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$vadovasRoleId", "organizationalUnitId": "$guildUnitId", "expiresInHours": 48 }""")
        }
        val guildCode = Json.parseToJsonElement(guildInvite.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val acceptResponse = client.post("/api/invitations/accept") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $existingToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "code": "$guildCode" }""")
        }

        assertEquals(HttpStatusCode.OK, acceptResponse.status)
        val body = Json.parseToJsonElement(acceptResponse.bodyAsText()).jsonObject
        assertEquals("Vadovas", body["roleName"]?.jsonPrimitive?.content)
        assertEquals(guildUnitId, body["organizationalUnitId"]?.jsonPrimitive?.content)

        transaction {
            exec("""
                SELECT COUNT(*) AS count
                FROM unit_assignments
                WHERE user_id = '$existingUserId'
                    AND organizational_unit_id = '$guildUnitId'
                    AND assignment_type = 'MEMBER'
                    AND left_at IS NULL
            """.trimIndent()) { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("count"))
            }
        }
    }

    @Test
    fun `existing member accepting principal unit leader invite fails when slot becomes occupied`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Skautai", "SKAUTU_DRAUGOVE")
        val skautasRoleId = getRoleId(tuntasId, "Skautas")
        val draugininkasRoleId = getRoleId(tuntasId, "Draugininkas")

        suspend fun registerExisting(email: String): String {
            val invite = client.post("/api/invitations") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                header("X-Tuntas-Id", tuntasId)
                setBody("""{ "roleId": "$skautasRoleId", "expiresInHours": 48 }""")
            }
            val code = Json.parseToJsonElement(invite.bodyAsText())
                .jsonObject["code"]!!.jsonPrimitive.content
            val register = client.post("/api/auth/register/invite") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                        "name": "Esamas",
                        "surname": "Narys",
                        "email": "$email",
                        "password": "testas123",
                        "inviteCode": "$code"
                    }
                """.trimIndent())
            }
            return Json.parseToJsonElement(register.bodyAsText())
                .jsonObject["token"]!!.jsonPrimitive.content
        }

        val firstToken = registerExisting("first-existing@test.com")
        val secondToken = registerExisting("second-existing@test.com")

        fun leaderInviteBody() = """{ "roleId": "$draugininkasRoleId", "organizationalUnitId": "$unitId", "expiresInHours": 48 }"""
        val firstLeaderCode = Json.parseToJsonElement(client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(leaderInviteBody())
        }.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
        val secondLeaderCode = Json.parseToJsonElement(client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(leaderInviteBody())
        }.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

        val firstAccept = client.post("/api/invitations/accept") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $firstToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "code": "$firstLeaderCode" }""")
        }
        assertEquals(HttpStatusCode.OK, firstAccept.status)

        val secondAccept = client.post("/api/invitations/accept") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $secondToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "code": "$secondLeaderCode" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, secondAccept.status)
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
                "password": "testas123",
                "tuntasName": "Test Tuntas",
                "tuntasKrastas": "Vilniaus"
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
                "password": "testas123",
                "tuntasName": "Test Tuntas",
                "tuntasKrastas": "Vilniaus"
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
            "password": "testas123",
            "tuntasName": "Test Tuntas Reject",
            "tuntasKrastas": "Vilniaus"
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
            "password": "testas123",
            "tuntasName": "Test Tuntas Active",
            "tuntasKrastas": "Vilniaus"
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
