package lt.skautai

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.createUnit
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.TestHelper.registerInvitedUser
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.plugins.configureSerialization
import lt.skautai.routes.userRoutes
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mindrot.jbcrypt.BCrypt
import java.util.Date
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRoutesTest {

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

    private fun ApplicationTestBuilder.configureUserRoutesWithLenientAuth() {
        val config = TestHelper.buildTestConfig()
        environment {
            this.config = config
        }
        application {
            install(Authentication) {
                jwt("auth-jwt") {
                    realm = config.property("jwt.realm").getString()
                    verifier(
                        JWT.require(Algorithm.HMAC256(config.property("jwt.secret").getString()))
                            .withAudience(config.property("jwt.audience").getString())
                            .withIssuer(config.property("jwt.issuer").getString())
                            .build()
                    )
                    validate { credential ->
                        val userId = credential.payload.getClaim("userId").asString()
                        val tokenType = credential.payload.getClaim("type").asString()
                        val tokenUse = credential.payload.getClaim("tokenUse").asString()
                        if (userId != null && tokenType == "user" && tokenUse == "access") {
                            JWTPrincipal(credential.payload)
                        } else {
                            null
                        }
                    }
                }
            }
            configureSerialization()
            install(DefaultHeaders)
            install(StatusPages) {
                exception<Throwable> { call, _ ->
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
                }
            }
            routing {
                userRoutes()
            }
        }
    }

    private fun buildAccessToken(userId: UUID, email: String = "ghost@test.com"): String {
        val config = TestHelper.buildTestConfig()
        return JWT.create()
            .withAudience(config.property("jwt.audience").getString())
            .withIssuer(config.property("jwt.issuer").getString())
            .withClaim("userId", userId.toString())
            .withClaim("email", email)
            .withClaim("type", "user")
            .withClaim("tokenUse", "access")
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256(config.property("jwt.secret").getString()))
    }

    private fun findUserIdByEmail(email: String): String {
        var userId = ""
        transaction {
            exec("SELECT id FROM users WHERE email = '$email' LIMIT 1") { rs ->
                if (rs.next()) userId = rs.getString("id")
            }
        }
        return userId
    }

    @Test
    fun `get my profile returns current user`() = testApplication {
        configureFullApp()
        val (token, _) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/users/me") {
            header("Authorization", "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("tuntininkas@test.com", body["email"]!!.jsonPrimitive.content)
        assertEquals("Test", body["name"]!!.jsonPrimitive.content)
        assertNotNull(body["createdAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get my profile returns 404 when user record is missing`() = testApplication {
        configureUserRoutesWithLenientAuth()
        val token = buildAccessToken(UUID.randomUUID())

        val response = client.get("/api/users/me") {
            header("Authorization", "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Vartotojas nerastas.", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `update my profile trims and normalizes values`() = testApplication {
        configureFullApp()
        val email = "Profile@TEST.com"
        val (token, _) = client.registerAndActivateTuntininkas(email = email)
        val userId = findUserIdByEmail("profile@test.com")

        val response = client.put("/api/users/me/profile") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody(
                """
                {
                    "name": "  Updated  ",
                    "surname": "  User  ",
                    "email": "  MixedCase@Example.com  ",
                    "phone": "  +370 6123456  "
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(userId, body["userId"]!!.jsonPrimitive.content)
        assertEquals("Updated", body["name"]!!.jsonPrimitive.content)
        assertEquals("User", body["surname"]!!.jsonPrimitive.content)
        assertEquals("mixedcase@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals("+370 6123456", body["phone"]!!.jsonPrimitive.content)
    }

    @Test
    fun `update my profile rejects duplicate email`() = testApplication {
        configureFullApp()
        val (token, _) = client.registerAndActivateTuntininkas(email = "first-profile@test.com")
        client.registerAndActivateTuntininkas(email = "second-profile@test.com", tuntasName = "Second Tuntas")

        val response = client.put("/api/users/me/profile") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody(
                """
                {
                    "name": "First",
                    "surname": "User",
                    "email": "second-profile@test.com",
                    "phone": null
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Šis el. paštas jau užregistruotas.", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `update my profile validates required and formatted fields`() = testApplication {
        configureFullApp()
        val (token, _) = client.registerAndActivateTuntininkas()

        val blankName = client.put("/api/users/me/profile") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{ "name": " ", "surname": "User", "email": "valid@test.com", "phone": null }""")
        }
        assertEquals(HttpStatusCode.BadRequest, blankName.status)
        assertEquals(
            "Įveskite vardą.",
            Json.parseToJsonElement(blankName.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val blankSurname = client.put("/api/users/me/profile") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{ "name": "User", "surname": " ", "email": "valid@test.com", "phone": null }""")
        }
        assertEquals(HttpStatusCode.BadRequest, blankSurname.status)
        assertEquals(
            "Įveskite pavardę.",
            Json.parseToJsonElement(blankSurname.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val invalidEmail = client.put("/api/users/me/profile") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{ "name": "User", "surname": "Valid", "email": "not-an-email", "phone": null }""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidEmail.status)
        assertEquals(
            "Įveskite teisingą el. pašto adresą.",
            Json.parseToJsonElement(invalidEmail.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val invalidPhone = client.put("/api/users/me/profile") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{ "name": "User", "surname": "Valid", "email": "valid@test.com", "phone": "abc" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidPhone.status)
        assertEquals(
            "Įveskite teisingą telefono numerį.",
            Json.parseToJsonElement(invalidPhone.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `update my profile returns 404 when authenticated user record is missing`() = testApplication {
        configureUserRoutesWithLenientAuth()
        val token = buildAccessToken(UUID.randomUUID())

        val response = client.put("/api/users/me/profile") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{ "name": "Ghost", "surname": "User", "email": "ghost@test.com", "phone": null }""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Vartotojas nerastas.", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `change password validates input and current password`() = testApplication {
        configureFullApp()
        val email = "tuntininkas@test.com"
        val (token, _) = client.registerAndActivateTuntininkas(email = email)
        val userId = findUserIdByEmail(email)

        val blankCurrent = client.put("/api/users/me/password") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{ "currentPassword": "", "newPassword": "newpass123" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, blankCurrent.status)
        assertEquals(
            "Įveskite dabartinį slaptažodį.",
            Json.parseToJsonElement(blankCurrent.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val weakPassword = client.put("/api/users/me/password") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{ "currentPassword": "testas123", "newPassword": "short" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, weakPassword.status)
        assertEquals(
            "Slaptažodis turi būti bent 8 simbolių.",
            Json.parseToJsonElement(weakPassword.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val samePassword = client.put("/api/users/me/password") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{ "currentPassword": "testas123", "newPassword": "testas123" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, samePassword.status)
        assertEquals(
            "Naujas slaptažodis turi skirtis nuo dabartinio.",
            Json.parseToJsonElement(samePassword.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val invalidCurrent = client.put("/api/users/me/password") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{ "currentPassword": "wrongpass1", "newPassword": "newpass123" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidCurrent.status)
        assertEquals(
            "Dabartinis slaptažodis neteisingas.",
            Json.parseToJsonElement(invalidCurrent.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val success = client.put("/api/users/me/password") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{ "currentPassword": "testas123", "newPassword": "newpass123" }""")
        }
        assertEquals(HttpStatusCode.OK, success.status)
        assertEquals(
            "Slaptažodis pakeistas.",
            Json.parseToJsonElement(success.bodyAsText()).jsonObject["message"]!!.jsonPrimitive.content
        )

        transaction {
            exec("SELECT password_hash FROM users WHERE id = '$userId'") { rs ->
                assertTrue(rs.next())
                assertTrue(BCrypt.checkpw("newpass123", rs.getString("password_hash")))
            }
        }
    }

    @Test
    fun `change password returns 404 when authenticated user record is missing`() = testApplication {
        configureUserRoutesWithLenientAuth()
        val token = buildAccessToken(UUID.randomUUID())

        val response = client.put("/api/users/me/password") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{ "currentPassword": "testas123", "newPassword": "newpass123" }""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Vartotojas nerastas.", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get permissions validates header membership and returns resolved permissions`() = testApplication {
        configureFullApp()
        val (leaderToken, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(leaderToken, tuntasId, "User Route Unit")
        val (memberToken, _) = client.registerInvitedUser(
            inviterToken = leaderToken,
            tuntasId = tuntasId,
            roleName = "Draugininkas",
            email = TestHelper.randomEmail("draugininkas-user-routes"),
            organizationalUnitId = unitId
        )

        val missingHeader = client.get("/api/users/me/permissions") {
            header("Authorization", "Bearer $memberToken")
        }
        assertEquals(HttpStatusCode.BadRequest, missingHeader.status)
        assertEquals(
            "Pirmiausia pasirinkite tuntą.",
            Json.parseToJsonElement(missingHeader.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val invalidHeader = client.get("/api/users/me/permissions") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", "not-a-uuid")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidHeader.status)
        assertEquals(
            "Neteisingas tunto ID.",
            Json.parseToJsonElement(invalidHeader.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val otherTuntasId = UUID.randomUUID().toString()
        transaction {
            exec(
                """
                INSERT INTO tuntai (id, name, krastas, status)
                VALUES ('$otherTuntasId'::uuid, 'Unrelated Tuntas', 'Vilniaus', 'ACTIVE')
                """.trimIndent()
            )
        }
        val nonMember = client.get("/api/users/me/permissions") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", otherTuntasId)
        }
        assertEquals(HttpStatusCode.Forbidden, nonMember.status)
        assertEquals(
            "Nesate šio tunto narys.",
            Json.parseToJsonElement(nonMember.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val success = client.get("/api/users/me/permissions") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, success.status)
        val permissions = Json.parseToJsonElement(success.bodyAsText()).jsonObject["permissions"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("members.view" in permissions)
        assertTrue("members.view:OWN_UNIT" in permissions)
        assertTrue("invitations.create:OWN_UNIT" in permissions)
    }

    @Test
    fun `get my tuntai includes active pending and rejected memberships`() = testApplication {
        configureFullApp()
        val email = "multi-tuntas@test.com"
        val (token, _) = client.registerAndActivateTuntininkas(email = email, tuntasName = "Primary Tuntas")
        val userId = findUserIdByEmail(email)

        val pendingId = UUID.randomUUID().toString()
        val rejectedId = UUID.randomUUID().toString()
        val suspendedId = UUID.randomUUID().toString()
        transaction {
            exec(
                """
                INSERT INTO tuntai (id, name, krastas, status) VALUES
                    ('$pendingId'::uuid, 'Pending Tuntas', 'Vilniaus', 'PENDING'),
                    ('$rejectedId'::uuid, 'Rejected Tuntas', 'Vilniaus', 'REJECTED'),
                    ('$suspendedId'::uuid, 'Suspended Tuntas', 'Vilniaus', 'SUSPENDED');
                INSERT INTO user_tuntas_memberships (user_id, tuntas_id) VALUES
                    ('$userId'::uuid, '$pendingId'::uuid),
                    ('$userId'::uuid, '$rejectedId'::uuid),
                    ('$userId'::uuid, '$suspendedId'::uuid)
                """.trimIndent()
            )
        }

        val response = client.get("/api/users/me/tuntai") {
            header("Authorization", "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val names = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            .toSet()
        assertTrue("Primary Tuntas" in names)
        assertTrue("Pending Tuntas" in names)
        assertTrue("Rejected Tuntas" in names)
        assertTrue("Suspended Tuntas" !in names)
    }

    @Test
    fun `leave tuntas validates id membership and cleans up assignments`() = testApplication {
        configureFullApp()
        val (leaderToken, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(leaderToken, tuntasId, "Leave Unit")
        val (memberToken, userId) = client.registerInvitedUser(
            inviterToken = leaderToken,
            tuntasId = tuntasId,
            roleName = "Skautas",
            email = TestHelper.randomEmail("leave-member"),
            organizationalUnitId = unitId
        )
        val deputyRoleId = TestHelper.getRoleId(tuntasId, "Draugininko pavaduotojas")

        transaction {
            exec(
                """
                INSERT INTO user_leadership_roles (
                    user_id, role_id, tuntas_id, organizational_unit_id, assigned_by_user_id, term_status
                ) VALUES (
                    '$userId'::uuid,
                    '$deputyRoleId'::uuid,
                    '$tuntasId'::uuid,
                    '$unitId'::uuid,
                    (SELECT id FROM users WHERE email = 'tuntininkas@test.com' LIMIT 1),
                    'ACTIVE'
                )
                """.trimIndent()
            )
        }

        val invalidId = client.post("/api/users/me/tuntai/not-a-uuid/leave") {
            header("Authorization", "Bearer $memberToken")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidId.status)
        assertEquals(
            "Neteisingas tunto ID.",
            Json.parseToJsonElement(invalidId.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val randomTuntasId = UUID.randomUUID().toString()
        val notMember = client.post("/api/users/me/tuntai/$randomTuntasId/leave") {
            header("Authorization", "Bearer $memberToken")
        }
        assertEquals(HttpStatusCode.BadRequest, notMember.status)
        assertEquals(
            "Nesate šio tunto narys.",
            Json.parseToJsonElement(notMember.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val success = client.post("/api/users/me/tuntai/$tuntasId/leave") {
            header("Authorization", "Bearer $memberToken")
        }
        assertEquals(HttpStatusCode.OK, success.status)
        assertEquals(
            "Tuntas paliktas.",
            Json.parseToJsonElement(success.bodyAsText()).jsonObject["message"]!!.jsonPrimitive.content
        )

        transaction {
            exec("SELECT left_at FROM user_tuntas_memberships WHERE user_id = '$userId'::uuid AND tuntas_id = '$tuntasId'::uuid") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
            }
            exec("SELECT left_at, term_status FROM user_leadership_roles WHERE user_id = '$userId'::uuid AND tuntas_id = '$tuntasId'::uuid") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
                assertEquals("RESIGNED", rs.getString("term_status"))
            }
            exec("SELECT left_at FROM unit_assignments WHERE user_id = '$userId'::uuid AND tuntas_id = '$tuntasId'::uuid") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
            }
            exec("SELECT COUNT(*) AS count FROM user_ranks WHERE user_id = '$userId'::uuid AND tuntas_id = '$tuntasId'::uuid") { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt("count"))
            }
        }
    }
}
