package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.routes.authRoutes
import lt.skautai.services.AuthService
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthRoutesTest {

    @BeforeAll
    fun setup() {
        val config = com.typesafe.config.ConfigFactory.load("test")
        val dbUrl = config.getString("test.database.url")
        val dbUser = config.getString("test.database.user")
        val dbPassword = config.getString("test.database.password")

        Database.connect(
            url = dbUrl,
            driver = "org.postgresql.Driver",
            user = dbUser,
            password = dbPassword
        )

        transaction {
            val schema = object {}.javaClass
                .getResource("/schema.sql")
                ?.readText()
                ?: error("schema.sql not found in resources")
            exec(schema)
        }
    }

    @AfterAll
    fun teardown() {
        transaction {
            exec("""
                DROP SCHEMA public CASCADE;
                CREATE SCHEMA public;
            """.trimIndent())
        }
    }


    @BeforeEach
    fun cleanTables() {
        transaction {
            exec("""
                TRUNCATE TABLE 
                    users, tuntai, super_admins, user_roles, role_permissions,
                    roles, permissions, locations, organizational_units,
                    user_tuntas_memberships, invitations
                CASCADE
            """.trimIndent())
        }
    }

    private fun ApplicationTestBuilder.configureApp() {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "jwt.secret" to "test-secret-key-minimum-32-characters!!",
                "jwt.issuer" to "lt.skautai.test",
                "jwt.audience" to "lt.skautai.test.app",
                "jwt.realm" to "test"
            )
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        application {
            val authService = AuthService(environment)
            routing { authRoutes(authService) }
        }
    }

    @Test
    fun `register tuntininkas returns 201 with token`() = testApplication {
        configureApp()

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
        configureApp()

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
        configureApp()

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
        configureApp()

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
        configureApp()

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
        configureApp()

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
        configureApp()

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
}