package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.net.URI

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountDeletionRoutesTest {
    @BeforeAll
    fun setup() = TestHelper.setupDatabase()

    @AfterAll
    fun teardown() = TestHelper.teardownDatabase()

    @BeforeEach
    fun clean() = TestHelper.cleanTables()

    @Test
    fun `app request requires current password and sends confirmation`() = testApplication {
        configureFullApp()
        val (token, _) = client.registerAndActivateTuntininkas()

        val wrongPassword = client.post("/api/users/me/account-deletion") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"wrong-password"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, wrongPassword.status)
        assertNull(TestHelper.lastAccountDeletionLink)

        val accepted = client.post("/api/users/me/account-deletion") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"testas123"}""")
        }
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        assertNotNull(TestHelper.lastAccountDeletionLink)
    }

    @Test
    fun `confirmation anonymizes account and invalidates access`() = testApplication {
        configureFullApp()
        val email = "delete-me@test.com"
        val (token, _) = client.registerAndActivateTuntininkas(email = email)

        client.post("/api/users/me/account-deletion") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"testas123"}""")
        }
        val confirmationUrl = TestHelper.lastAccountDeletionLink!!
        val rawToken = URI(confirmationUrl).rawQuery.substringAfter("token=")

        val confirmationPage = client.get("/account-deletion/confirm?token=$rawToken")
        assertEquals(HttpStatusCode.OK, confirmationPage.status)
        assertTrue(confirmationPage.bodyAsText().contains("Visam laikui ištrinti paskyrą"))

        val confirmed = client.submitForm(
            url = "/account-deletion/confirm",
            formParameters = parametersOf("token", rawToken)
        )
        assertEquals(HttpStatusCode.OK, confirmed.status)
        assertTrue(confirmed.bodyAsText().contains("Paskyra ištrinta"))

        transaction {
            exec(
                "SELECT name, surname, email, phone, deleted_at FROM users WHERE email LIKE 'deleted-%@deleted.invalid'"
            ) { rs ->
                assertTrue(rs.next())
                assertEquals("Ištrintas", rs.getString("name"))
                assertEquals("naudotojas", rs.getString("surname"))
                assertNull(rs.getString("phone"))
                assertNotNull(rs.getTimestamp("deleted_at"))
            }
        }

        val oldAccess = client.get("/api/users/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Unauthorized, oldAccess.status)

        val oldLogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"testas123"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, oldLogin.status)

        val reused = client.submitForm(
            url = "/account-deletion/confirm",
            formParameters = parametersOf("token", rawToken)
        )
        assertEquals(HttpStatusCode.BadRequest, reused.status)
    }

    @Test
    fun `public request does not disclose whether account exists`() = testApplication {
        configureFullApp()
        val response = client.post("/api/account-deletion/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"missing@test.com"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val message = Json.parseToJsonElement(response.bodyAsText()).jsonObject["message"]!!.jsonPrimitive.content
        assertTrue(message.contains("Jei paskyra"))
    }

    @Test
    fun `public deletion and privacy pages are served`() = testApplication {
        configureFullApp()
        val deletionPage = client.get("/delete-account.html")
        assertEquals(HttpStatusCode.OK, deletionPage.status)
        assertTrue(deletionPage.bodyAsText().contains("Paskyros ir duomenų ištrynimas"))

        val privacyPage = client.get("/privacy.html")
        assertEquals(HttpStatusCode.OK, privacyPage.status)
        assertTrue(privacyPage.bodyAsText().contains("Privatumo politika"))
    }
}
