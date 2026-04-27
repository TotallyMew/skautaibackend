package lt.skautai

import io.ktor.client.HttpClient
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
class LocationRoutesTest {

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

    private suspend fun HttpClient.registerSecondUser(
        token: String,
        tuntasId: String,
        email: String = "location-member@test.com"
    ): Pair<String, String> {
        val roleId = TestHelper.getRoleId(tuntasId, "Skautas")
        val inviteResponse = post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId", "expiresInHours": 48 }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Location",
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
    fun `create location returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Garazas",
                    "address": "Vilnius g. 1",
                    "description": "Pagrindinis sandÄ—lis"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Garazas", body["name"]?.jsonPrimitive?.content)
        assertEquals("Vilnius g. 1", body["address"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create location without token returns 401`() = testApplication {
        configureFullApp()
        val (_, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Garazas" }""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `create location without tuntas header returns 400`() = testApplication {
        configureFullApp()
        val (token, _) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{ "name": "Garazas" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `get locations returns 200 with list`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        client.post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Garazas" }""")
        }

        client.post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "SandÄ—lis" }""")
        }

        val response = client.get("/api/locations") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(2, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `get single location returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createResponse = client.post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Garazas" }""")
        }

        val locationId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.get("/api/locations/$locationId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Garazas", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `private location is visible only to owner`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (otherToken, _) = client.registerSecondUser(token, tuntasId)

        val createResponse = client.post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Private shelf", "visibility": "PRIVATE" }""")
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val locationId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val listResponse = client.get("/api/locations") {
            header("Authorization", "Bearer $otherToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val locations = Json.parseToJsonElement(listResponse.bodyAsText()).jsonObject["locations"]!!.jsonArray
        assertTrue(locations.none { it.jsonObject["id"]?.jsonPrimitive?.content == locationId })

        val detailResponse = client.get("/api/locations/$locationId") {
            header("Authorization", "Bearer $otherToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.NotFound, detailResponse.status)
    }

    @Test
    fun `get nonexistent location returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/locations/00000000-0000-0000-0000-000000000000") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `update location returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createResponse = client.post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Garazas" }""")
        }

        val locationId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.put("/api/locations/$locationId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Atnaujintas garazas",
                    "address": "Kauno g. 5"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Atnaujintas garazas", body["name"]?.jsonPrimitive?.content)
        assertEquals("Kauno g. 5", body["address"]?.jsonPrimitive?.content)
    }

    @Test
    fun `delete location returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createResponse = client.post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Garazas" }""")
        }

        val locationId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.delete("/api/locations/$locationId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val getResponse = client.get("/api/locations/$locationId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }

    @Test
    fun `delete location with active items returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val locationResponse = client.post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Garazas" }""")
        }

        val locationId = Json.parseToJsonElement(locationResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Palapine",
                    "type": "COLLECTIVE", "category": "CAMPING",
                    "quantity": 1,
                    "locationId": "$locationId"
                }
            """.trimIndent())
        }

        val response = client.delete("/api/locations/$locationId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
