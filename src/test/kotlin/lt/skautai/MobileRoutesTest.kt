package lt.skautai

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MobileRoutesTest {

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
    fun `home summary returns SQL backed counts without active reservation payload`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas(email = TestHelper.randomEmail("mobile-home"))

        client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Mobile Palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1 }""")
        }

        val response = client.get("/api/mobile/home-summary") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["sharedInventoryCount"]!!.jsonPrimitive.int)
        assertEquals(0, body["activeReservations"]?.jsonArray?.size ?: 0)
    }

    @Test
    fun `cache state returns resource totals and max updated timestamp`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas(email = TestHelper.randomEmail("mobile-cache"))

        client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Cache Palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1 }""")
        }

        val response = client.get("/api/mobile/cache-state") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val resources = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["resources"]!!
            .jsonArray
            .map { it.jsonObject }
            .associateBy { it["resource"]!!.jsonPrimitive.content }
        assertEquals(1, resources["items"]!!["total"]!!.jsonPrimitive.int)
        assertNotNull(resources["items"]!!["maxUpdatedAt"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content)
        assertEquals(1, resources["members"]!!["total"]!!.jsonPrimitive.int)
        assertNotNull(resources["members"]!!["maxUpdatedAt"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content)
    }
}
