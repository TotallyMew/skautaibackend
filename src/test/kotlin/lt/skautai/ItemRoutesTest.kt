package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemRoutesTest {

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
    fun `create item returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Palapine",
                    "type": "COLLECTIVE", "category": "CAMPING",
                    "quantity": 2
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Palapine", body["name"]?.jsonPrimitive?.content)
        assertEquals("COLLECTIVE", body["type"]?.jsonPrimitive?.content)
        assertEquals("CAMPING", body["category"]?.jsonPrimitive?.content)
        assertEquals("ACTIVE", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create item without token returns 401`() = testApplication {
        configureFullApp()
        val (_, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Palapine",
                    "type": "COLLECTIVE", "category": "CAMPING",
                    "quantity": 1
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `create item without tuntas header returns 400`() = testApplication {
        configureFullApp()
        val (token, _) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""
                {
                    "name": "Palapine",
                    "type": "COLLECTIVE", "category": "CAMPING",
                    "quantity": 1
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `get items returns 200 with list`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1 }""")
        }

        val response = client.get("/api/items") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["items"])
        assertEquals(1, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `get single item returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Kirvukas", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1 }""")
        }

        val itemId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.get("/api/items/$itemId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Kirvukas", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `get nonexistent item returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/items/00000000-0000-0000-0000-000000000000") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `update item returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1 }""")
        }

        val itemId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.put("/api/items/$itemId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Palapine atnaujinta", "quantity": 3, "condition": "DAMAGED" }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Palapine atnaujinta", body["name"]?.jsonPrimitive?.content)
        assertEquals(3, body["quantity"]?.jsonPrimitive?.content?.toInt())
        assertEquals("DAMAGED", body["condition"]?.jsonPrimitive?.content)
    }

    @Test
    fun `delete item returns 200 and item becomes inactive`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1 }""")
        }

        val itemId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val deleteResponse = client.delete("/api/items/$itemId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        val getResponse = client.get("/api/items/$itemId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, getResponse.status)
        val body = Json.parseToJsonElement(getResponse.bodyAsText()).jsonObject
        assertEquals("INACTIVE", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create item with invalid category returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Palapine", "category": "INVALID", "quantity": 1 }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `create item with custodian unit returns 201 with custodianId set`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val unitResponse = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vilkai", "type": "VILKU_DRAUGOVE" }""")
        }
        val unitId = Json.parseToJsonElement(unitResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1, "custodianId": "$unitId" }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(unitId, body["custodianId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `get items filtered by custodianId returns only that unit items`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val unitResponse = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vilkai", "type": "VILKU_DRAUGOVE" }""")
        }
        val unitId = Json.parseToJsonElement(unitResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        // Item with custodian
        client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1, "custodianId": "$unitId" }""")
        }
        // Item without custodian (tuntas storage)
        client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Kirvukas", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1 }""")
        }

        val response = client.get("/api/items?custodianId=$unitId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["total"]?.jsonPrimitive?.content?.toInt())
    }
}
