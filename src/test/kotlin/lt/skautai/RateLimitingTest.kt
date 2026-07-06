package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RateLimitingTest {
    @BeforeAll
    fun setup() = TestHelper.setupDatabase()

    @AfterAll
    fun teardown() = TestHelper.teardownDatabase()

    @BeforeEach
    fun clean() = TestHelper.cleanTables()

    @Test
    fun `registration is limited with Lithuanian error message`() = testApplication {
        configureFullApp(useProductionRateLimits = true)

        repeat(3) { index ->
            client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "name": "Jonas",
                        "surname": "Jonaitis",
                        "email": "rate-$index@test.com",
                        "password": "testas123",
                        "tuntasName": "Rate Tuntas $index",
                        "tuntasKrastas": "Vilniaus"
                    }
                    """.trimIndent()
                )
            }
        }

        val limited = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "name": "Jonas",
                    "surname": "Jonaitis",
                    "email": "rate-limited@test.com",
                    "password": "testas123",
                    "tuntasName": "Rate Tuntas Limited",
                    "tuntasKrastas": "Vilniaus"
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue(limited.headers[HttpHeaders.RetryAfter]?.isNotBlank() == true)
        val error = Json.parseToJsonElement(limited.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        assertTrue(error.startsWith("Per daug užklausų."))
    }

    @Test
    fun `public email requests are limited with Lithuanian error message`() = testApplication {
        configureFullApp(useProductionRateLimits = true)

        repeat(10) {
            client.post("/api/auth/forgot-password") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"missing-$it@test.com"}""")
            }
        }

        val limited = client.post("/api/auth/forgot-password") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"missing-limited@test.com"}""")
        }

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue(limited.headers[HttpHeaders.RetryAfter]?.isNotBlank() == true)
        val error = Json.parseToJsonElement(limited.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        assertTrue(error.startsWith("Per daug užklausų."))
    }
}
