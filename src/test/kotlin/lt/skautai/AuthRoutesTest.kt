package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import lt.skautai.TestHelper.configureFullApp


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
}