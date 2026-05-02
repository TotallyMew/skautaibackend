package lt.skautai

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

    private suspend fun ApplicationTestBuilder.createUnit(
        token: String,
        tuntasId: String,
        name: String,
        type: String = "SKAUTU_DRAUGOVE"
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

    private suspend fun ApplicationTestBuilder.registerUserWithRole(
        token: String,
        tuntasId: String,
        roleName: String,
        email: String,
        organizationalUnitId: String? = null
    ): Pair<String, String> {
        val roleId = TestHelper.getRoleId(tuntasId, roleName)
        val unitField = organizationalUnitId?.let { ", \"organizationalUnitId\": \"$it\"" }.orEmpty()
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId"$unitField, "expiresInHours": 48 }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Scoped",
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
        assertNotNull(body["qrToken"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create item with duplicate name returns 409 until user chooses action`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 2 }""")
        }

        val response = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "  Palapine  ", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1 }""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Palapine", body["duplicateItem"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create item can add quantity to existing duplicate`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val existingResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Kirvis", "type": "COLLECTIVE", "category": "TOOLS", "quantity": 2 }""")
        }
        val existingId = Json.parseToJsonElement(existingResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """{
                    "name": "Kirvis",
                    "type": "COLLECTIVE",
                    "category": "TOOLS",
                    "quantity": 3,
                    "duplicateHandling": "ADD_TO_EXISTING",
                    "duplicateTargetItemId": "$existingId"
                }""".trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(existingId, body["id"]!!.jsonPrimitive.content)
        assertEquals(5, body["quantity"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `create item can force new record even when duplicate exists`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Puodas", "type": "COLLECTIVE", "category": "COOKING", "quantity": 1 }""")
        }

        val response = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """{
                    "name": "Puodas",
                    "type": "COLLECTIVE",
                    "category": "COOKING",
                    "quantity": 1,
                    "duplicateHandling": "CREATE_NEW"
                }""".trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)

        val listResponse = client.get("/api/items?type=COLLECTIVE&category=COOKING") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val items = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["items"]!!.jsonArray
            .filter { it.jsonObject["name"]!!.jsonPrimitive.content == "Puodas" }
        assertEquals(2, items.size)
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
    fun `resolve qr token returns item id for accessible item`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Kirvis", "type": "COLLECTIVE", "category": "TOOLS", "quantity": 1 }""")
        }
        val itemBody = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val itemId = itemBody["id"]!!.jsonPrimitive.content
        val qrToken = itemBody["qrToken"]!!.jsonPrimitive.content

        val response = client.get("/api/items/resolve-qr/$qrToken") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(itemId, body["itemId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `resolve qr token returns 404 for inaccessible item`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val ownUnitId = createUnit(token, tuntasId, "Skautai 1")
        val otherUnitId = createUnit(token, tuntasId, "Skautai 2")

        val createResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Svetimas kirvis", "type": "COLLECTIVE", "category": "TOOLS", "quantity": 1, "custodianId": "$otherUnitId" }""")
        }
        val qrToken = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["qrToken"]!!.jsonPrimitive.content
        val (memberToken, _) = registerUserWithRole(token, tuntasId, "Skautas", "qr-scope@test.com", ownUnitId)

        val response = client.get("/api/items/resolve-qr/$qrToken") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
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
    fun `create item ignores client supplied transferred origin`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Bandymas",
                    "type": "COLLECTIVE",
                    "category": "CAMPING",
                    "quantity": 1,
                    "origin": "TRANSFERRED_FROM_TUNTAS",
                    "sourceSharedItemId": "00000000-0000-0000-0000-000000000000"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("UNIT_ACQUIRED", body["origin"]?.jsonPrimitive?.content)
        assertEquals(null, body["sourceSharedItemId"])
    }

    @Test
    fun `individual item cannot be assigned to unit custodian`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Asmeniniai")

        val response = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Kuprine", "type": "INDIVIDUAL", "category": "CAMPING", "quantity": 1, "custodianId": "$unitId" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `update item can clear custodian with explicit flag`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Skautai")

        val createResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1, "custodianId": "$unitId" }""")
        }
        val itemId = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val response = client.put("/api/items/$itemId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "clearCustodianId": true }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(null, body["custodianId"])
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

    @Test
    fun `regular member sees only shared and own unit inventory`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val ownUnitId = createUnit(token, tuntasId, "Skautai 1")
        val otherUnitId = createUnit(token, tuntasId, "Skautai 2")

        client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Bendra palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1 }""")
        }
        client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Sava palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1, "custodianId": "$ownUnitId" }""")
        }
        val otherItemResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Svetima palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1, "custodianId": "$otherUnitId" }""")
        }
        val otherItemId = Json.parseToJsonElement(otherItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val (memberToken, _) = registerUserWithRole(token, tuntasId, "Skautas", "scoped-items@test.com", ownUnitId)

        val listResponse = client.get("/api/items") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val names = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["items"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            .toSet()
        assertEquals(setOf("Bendra palapine", "Sava palapine"), names)

        val detailResponse = client.get("/api/items/$otherItemId") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.NotFound, detailResponse.status)
    }

    @Test
    fun `unit leader cannot move item custody to another unit`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val ownUnitId = createUnit(token, tuntasId, "Skautai 1")
        val otherUnitId = createUnit(token, tuntasId, "Skautai 2")
        val (leaderToken, _) = registerUserWithRole(token, tuntasId, "Draugininkas", "custody-leader@test.com", ownUnitId)

        val itemResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Sava palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1, "custodianId": "$ownUnitId" }""")
        }
        val itemId = Json.parseToJsonElement(itemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val response = client.put("/api/items/$itemId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "custodianId": "$otherUnitId" }""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
