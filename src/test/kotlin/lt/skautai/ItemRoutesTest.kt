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
        type: String = "SKAUTU_DRAUGOVE",
        subType: String? = null
    ): String {
        val subTypeField = subType?.let { """, "subType": "$it"""" }.orEmpty()
        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "type": "$type"$subTypeField }""")
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
    fun `consume item decreases consumable quantity and records history`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """{
                    "name": "Dujos",
                    "type": "COLLECTIVE",
                    "category": "COOKING",
                    "quantity": 8,
                    "isConsumable": true,
                    "unitOfMeasure": "vnt.",
                    "minimumQuantity": 5
                }""".trimIndent()
            )
        }
        val itemId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val consumeResponse = client.post("/api/items/$itemId/consume") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantity": 3, "notes": "Renginys" }""")
        }

        assertEquals(HttpStatusCode.OK, consumeResponse.status)
        val body = Json.parseToJsonElement(consumeResponse.bodyAsText()).jsonObject
        assertEquals(5, body["quantity"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", body["isLowStock"]!!.jsonPrimitive.content)

        val historyResponse = client.get("/api/items/$itemId/history") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val history = Json.parseToJsonElement(historyResponse.bodyAsText())
            .jsonObject["entries"]!!.jsonArray
        assertTrue(history.any {
            it.jsonObject["eventType"]!!.jsonPrimitive.content == "CONSUMED" &&
                it.jsonObject["quantityChange"]!!.jsonPrimitive.content.toInt() == -3
        })
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
    fun `get items supports limit and offset pagination`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        listOf("A kuprine", "B puodas", "C virve").forEach { name ->
            client.post("/api/items") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                header("X-Tuntas-Id", tuntasId)
                setBody("""{ "name": "$name", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1 }""")
            }
        }

        val response = client.get("/api/items?limit=2&offset=0") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val names = body["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("A kuprine", "B puodas"), names)
        assertEquals(3, body["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, body["limit"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", body["hasMore"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get items rejects invalid pagination parameters`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val badLimit = client.get("/api/items?limit=0") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val badOffset = client.get("/api/items?offset=-1") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, badLimit.status)
        assertEquals(HttpStatusCode.BadRequest, badOffset.status)
    }

    @Test
    fun `get items filters by search query`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Zygiu palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1 }""")
        }
        client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Virtuves puodas", "type": "COLLECTIVE", "category": "COOKING", "quantity": 1 }""")
        }

        val response = client.get("/api/items?q=palap&limit=10") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val names = body["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("Zygiu palapine"), names)
        assertEquals(1, body["total"]!!.jsonPrimitive.content.toInt())
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
    fun `get single item with invalid id returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/items/not-a-uuid") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `item detail subroutes validate invalid ids and permission guards`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val ownUnitId = createUnit(token, tuntasId, "Own Unit")
        val (memberToken, _) = registerUserWithRole(token, tuntasId, "Skautas", "item-subroute-guard@test.com", ownUnitId)
        val sharedItemId = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Shared restock target", "type": "COLLECTIVE", "category": "TOOLS", "quantity": 1 }""")
        }.bodyAsText().let { Json.parseToJsonElement(it).jsonObject["id"]!!.jsonPrimitive.content }

        val badAssignments = client.get("/api/items/not-a-uuid/assignments") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, badAssignments.status)

        val badConditionLog = client.get("/api/items/not-a-uuid/condition-log") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, badConditionLog.status)

        val badTransfers = client.get("/api/items/not-a-uuid/transfers") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, badTransfers.status)

        val badHistory = client.get("/api/items/not-a-uuid/history") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, badHistory.status)

        val badListHeader = client.get("/api/items") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", "not-a-uuid")
        }
        assertEquals(HttpStatusCode.BadRequest, badListHeader.status)

        val forbiddenSharedRestock = client.post("/api/items/$sharedItemId/restock") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantity": 1 }""")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenSharedRestock.status)
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
    fun `resolve qr token with blank token returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/items/resolve-qr/%20") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
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
    fun `update item with invalid custodian id returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Invalid update", "type": "COLLECTIVE", "category": "TOOLS", "quantity": 1 }""")
        }
        val itemId = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val response = client.put("/api/items/$itemId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "custodianId": "not-a-uuid" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `regular member cannot create shared item`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val ownUnitId = createUnit(token, tuntasId, "Own Unit")
        val (memberToken, _) = registerUserWithRole(token, tuntasId, "Skautas", "shared-create-block@test.com", ownUnitId)

        val response = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Shared Stove", "type": "COLLECTIVE", "category": "COOKING", "quantity": 1 }""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
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
    fun `tuntas leadership cannot see senior unit owned inventory but sees shared transfers`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val seniorUnitId = createUnit(
            token,
            tuntasId,
            "Vyr. skautai",
            type = "VYR_SKAUTU_VIENETAS",
            subType = "DRAUGOVE"
        )
        val (leaderToken, _) = registerUserWithRole(
            token,
            tuntasId,
            "Vyr. skautu draugoves draugininkas",
            "senior-inventory-leader@test.com",
            seniorUnitId
        )

        val ownedResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """{ "name": "Slaptas inventorius", "type": "COLLECTIVE", "category": "OTHER", "quantity": 1, "custodianId": "$seniorUnitId" }"""
            )
        }
        assertEquals(HttpStatusCode.Created, ownedResponse.status)
        val ownedItemId = Json.parseToJsonElement(ownedResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val sharedResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Bendras katilas", "type": "COLLECTIVE", "category": "COOKING", "quantity": 2 }""")
        }
        val sharedItemId = Json.parseToJsonElement(sharedResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
        val transferResponse = client.post("/api/items/$sharedItemId/transfer-to-unit") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "targetUnitId": "$seniorUnitId", "quantity": 1 }""")
        }
        assertEquals(HttpStatusCode.OK, transferResponse.status)
        val transferredItemId = Json.parseToJsonElement(transferResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val listResponse = client.get("/api/items") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val visibleIds = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["items"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            .toSet()
        assertFalse(ownedItemId in visibleIds)
        assertTrue(transferredItemId in visibleIds)

        val hiddenDetail = client.get("/api/items/$ownedItemId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.NotFound, hiddenDetail.status)
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

    @Test
    fun `transfer shared item to unit is visible in transfers and history`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Skautai 1")

        val sharedItemResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Katilas", "type": "COLLECTIVE", "category": "COOKING", "quantity": 5 }""")
        }
        val sharedItemId = Json.parseToJsonElement(sharedItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val transferResponse = client.post("/api/items/$sharedItemId/transfer-to-unit") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "targetUnitId": "$unitId", "quantity": 2, "notes": "Perduota vienetui" }""")
        }

        assertEquals(HttpStatusCode.OK, transferResponse.status)
        val transferredBody = Json.parseToJsonElement(transferResponse.bodyAsText()).jsonObject
        val unitItemId = transferredBody["id"]!!.jsonPrimitive.content
        assertEquals(unitId, transferredBody["custodianId"]!!.jsonPrimitive.content)
        assertEquals("TRANSFERRED_FROM_TUNTAS", transferredBody["origin"]!!.jsonPrimitive.content)
        assertEquals(2, transferredBody["quantity"]!!.jsonPrimitive.content.toInt())

        val transfersResponse = client.get("/api/items/$sharedItemId/transfers") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, transfersResponse.status)
        val transfersBody = Json.parseToJsonElement(transfersResponse.bodyAsText()).jsonObject
        assertEquals(1, transfersBody["total"]!!.jsonPrimitive.content.toInt())

        val historyResponse = client.get("/api/items/$unitItemId/history") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, historyResponse.status)
        val historyBody = Json.parseToJsonElement(historyResponse.bodyAsText()).jsonObject
        assertEquals(1, historyBody["total"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `return transferred item to shared restores quantity and writes transfer`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Skautai 1")

        val sharedItemResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Kirvis", "type": "COLLECTIVE", "category": "TOOLS", "quantity": 4 }""")
        }
        val sharedItemId = Json.parseToJsonElement(sharedItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val transferResponse = client.post("/api/items/$sharedItemId/transfer-to-unit") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "targetUnitId": "$unitId", "quantity": 3, "notes": "Perduota" }""")
        }
        val unitItemId = Json.parseToJsonElement(transferResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val returnResponse = client.post("/api/items/$unitItemId/return-to-shared") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantity": 2, "notes": "Grazinta" }""")
        }

        assertEquals(HttpStatusCode.OK, returnResponse.status)
        val unitBody = Json.parseToJsonElement(returnResponse.bodyAsText()).jsonObject
        assertEquals(1, unitBody["quantity"]!!.jsonPrimitive.content.toInt())

        val sharedItemAfterReturn = client.get("/api/items/$sharedItemId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, sharedItemAfterReturn.status)
        val sharedBody = Json.parseToJsonElement(sharedItemAfterReturn.bodyAsText()).jsonObject
        assertEquals(3, sharedBody["quantity"]!!.jsonPrimitive.content.toInt())

        val transfersResponse = client.get("/api/items/$sharedItemId/transfers") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, transfersResponse.status)
        val transfersBody = Json.parseToJsonElement(transfersResponse.bodyAsText()).jsonObject
        assertEquals(2, transfersBody["total"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `updating item condition creates condition log entry`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Zibintas", "type": "INDIVIDUAL", "category": "LIGHTING", "quantity": 1, "condition": "GOOD" }""")
        }
        val itemId = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val updateResponse = client.put("/api/items/$itemId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "condition": "DAMAGED" }""")
        }
        assertEquals(HttpStatusCode.OK, updateResponse.status)

        val conditionLogResponse = client.get("/api/items/$itemId/condition-log") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, conditionLogResponse.status)
        val body = Json.parseToJsonElement(conditionLogResponse.bodyAsText()).jsonObject
        assertEquals(1, body["total"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `item review workflow supports unit approval shared approval and rejection`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Review Unit")
        val (leaderToken, _) = registerUserWithRole(token, tuntasId, "Draugininkas", "item-review-leader@test.com", unitId)
        val (submitterToken, _) = registerUserWithRole(token, tuntasId, "Vadovas", "item-review-submitter@test.com", unitId)
        val (memberToken, _) = registerUserWithRole(token, tuntasId, "Skautas", "item-review-member@test.com", unitId)

        val pendingUnitResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $submitterToken")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """{
                    "name": "Lauzo puodas",
                    "type": "COLLECTIVE",
                    "category": "COOKING",
                    "quantity": 2,
                    "custodianId": "$unitId"
                }""".trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, pendingUnitResponse.status)
        val pendingUnitBody = Json.parseToJsonElement(pendingUnitResponse.bodyAsText()).jsonObject
        val pendingUnitId = pendingUnitBody["id"]!!.jsonPrimitive.content
        assertEquals("PENDING_APPROVAL", pendingUnitBody["status"]!!.jsonPrimitive.content)

        val pendingSharedCreate = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Bendras katilas", "type": "COLLECTIVE", "category": "COOKING", "quantity": 1 }""")
        }
        val pendingSharedId = Json.parseToJsonElement(pendingSharedCreate.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val markSharedPending = client.put("/api/items/$pendingSharedId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "PENDING_APPROVAL" }""")
        }
        assertEquals(HttpStatusCode.OK, markSharedPending.status)

        val pendingListResponse = client.get("/api/items?status=PENDING_APPROVAL") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, pendingListResponse.status)
        val pendingItems = Json.parseToJsonElement(pendingListResponse.bodyAsText()).jsonObject["items"]!!.jsonArray
        val pendingIds = pendingItems.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        assertTrue(pendingUnitId in pendingIds)
        assertTrue(pendingSharedId in pendingIds)

        val memberReviewAttempt = client.post("/api/items/$pendingUnitId/review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $submitterToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "decision": "APPROVED" }""")
        }
        assertEquals(HttpStatusCode.Forbidden, memberReviewAttempt.status)

        val leaderSharedAttempt = client.post("/api/items/$pendingSharedId/review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "decision": "APPROVED" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, leaderSharedAttempt.status)

        val approveUnitResponse = client.post("/api/items/$pendingUnitId/review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "decision": "APPROVED" }""")
        }
        assertEquals(HttpStatusCode.OK, approveUnitResponse.status)
        val approvedUnitBody = Json.parseToJsonElement(approveUnitResponse.bodyAsText()).jsonObject
        assertEquals("ACTIVE", approvedUnitBody["status"]!!.jsonPrimitive.content)

        val approveSharedResponse = client.post("/api/items/$pendingSharedId/review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "decision": "APPROVED" }""")
        }
        assertEquals(HttpStatusCode.OK, approveSharedResponse.status)
        val approvedSharedBody = Json.parseToJsonElement(approveSharedResponse.bodyAsText()).jsonObject
        assertEquals("ACTIVE", approvedSharedBody["status"]!!.jsonPrimitive.content)

        val rejectedItemResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $submitterToken")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """{
                    "name": "Suluzusi spintele",
                    "type": "COLLECTIVE",
                    "category": "TOOLS",
                    "quantity": 1,
                    "custodianId": "$unitId"
                }""".trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, rejectedItemResponse.status)
        val rejectedItemId = Json.parseToJsonElement(rejectedItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val rejectResponse = client.post("/api/items/$rejectedItemId/review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "decision": "REJECTED", "rejectionReason": "Nebus naudojama" }""")
        }
        assertEquals(HttpStatusCode.OK, rejectResponse.status)
        val rejectedBody = Json.parseToJsonElement(rejectResponse.bodyAsText()).jsonObject
        assertEquals("INACTIVE", rejectedBody["status"]!!.jsonPrimitive.content)
        assertEquals("Nebus naudojama", rejectedBody["rejectionReason"]!!.jsonPrimitive.content)

        val repeatReviewResponse = client.post("/api/items/$pendingUnitId/review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "decision": "APPROVED" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, repeatReviewResponse.status)
    }

    @Test
    fun `approved unit item can be restocked and history reflects review and restock`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Restock Unit")
        val (leaderToken, _) = registerUserWithRole(token, tuntasId, "Draugininkas", "item-restock-leader@test.com", unitId)
        val (submitterToken, _) = registerUserWithRole(token, tuntasId, "Vadovas", "item-restock-submitter@test.com", unitId)

        val createPending = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $submitterToken")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """{
                    "name": "Vieneto kirvis",
                    "type": "COLLECTIVE",
                    "category": "TOOLS",
                    "quantity": 2,
                    "custodianId": "$unitId"
                }""".trimIndent()
            )
        }
        val itemId = Json.parseToJsonElement(createPending.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val approveResponse = client.post("/api/items/$itemId/review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "decision": "APPROVED" }""")
        }
        assertEquals(HttpStatusCode.OK, approveResponse.status)

        val invalidRestock = client.post("/api/items/$itemId/restock") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantity": 1, "purchaseDate": "2026/05/01" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidRestock.status)

        val restockResponse = client.post("/api/items/$itemId/restock") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantity": 3, "purchaseDate": "2026-05-01", "purchasePrice": 15.5, "notes": "Papildytas vieneto kiekis" }""")
        }
        assertEquals(HttpStatusCode.OK, restockResponse.status)
        val restockedBody = Json.parseToJsonElement(restockResponse.bodyAsText()).jsonObject
        assertEquals(5, restockedBody["quantity"]!!.jsonPrimitive.content.toInt())
        assertEquals("2026-05-01", restockedBody["purchaseDate"]!!.jsonPrimitive.content)
        assertEquals("Papildytas vieneto kiekis", restockedBody["notes"]!!.jsonPrimitive.content)

        val assignmentsResponse = client.get("/api/items/$itemId/assignments") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, assignmentsResponse.status)

        val historyResponse = client.get("/api/items/$itemId/history") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, historyResponse.status)
        val historyBody = Json.parseToJsonElement(historyResponse.bodyAsText()).jsonObject
        assertTrue(historyBody["total"]!!.jsonPrimitive.content.toInt() >= 2)

        val detailResponse = client.get("/api/items/$itemId") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, detailResponse.status)
        val detailBody = Json.parseToJsonElement(detailResponse.bodyAsText()).jsonObject
        assertEquals("ACTIVE", detailBody["status"]!!.jsonPrimitive.content)
        assertEquals(unitId, detailBody["custodianId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `only shared inventory manager can return or restock transferred inventory`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Transfer Unit")
        val (leaderToken, _) = registerUserWithRole(token, tuntasId, "Draugininkas", "item-transfer-leader@test.com", unitId)

        val sharedItemResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Pjuklas", "type": "COLLECTIVE", "category": "TOOLS", "quantity": 6 }""")
        }
        val sharedItemId = Json.parseToJsonElement(sharedItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val transferResponse = client.post("/api/items/$sharedItemId/transfer-to-unit") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "targetUnitId": "$unitId", "quantity": 4, "notes": "Perduota draugovei" }""")
        }
        assertEquals(HttpStatusCode.OK, transferResponse.status)
        val unitItemId = Json.parseToJsonElement(transferResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val forbiddenRestock = client.post("/api/items/$unitItemId/restock") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantity": 1 }""")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenRestock.status)

        val leaderReturn = client.post("/api/items/$unitItemId/return-to-shared") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantity": 3, "notes": "Grazinta i sandeli" }""")
        }
        assertEquals(HttpStatusCode.Forbidden, leaderReturn.status)

        val managerReturn = client.post("/api/items/$unitItemId/return-to-shared") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantity": 3, "notes": "Grazinta i sandeli" }""")
        }
        assertEquals(HttpStatusCode.OK, managerReturn.status)
        val returnedUnitBody = Json.parseToJsonElement(managerReturn.bodyAsText()).jsonObject
        assertEquals(1, returnedUnitBody["quantity"]!!.jsonPrimitive.content.toInt())
        assertEquals("ACTIVE", returnedUnitBody["status"]!!.jsonPrimitive.content)

        val sharedAfterReturn = client.get("/api/items/$sharedItemId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, sharedAfterReturn.status)
        val sharedBody = Json.parseToJsonElement(sharedAfterReturn.bodyAsText()).jsonObject
        assertEquals(5, sharedBody["quantity"]!!.jsonPrimitive.content.toInt())

        val transfersResponse = client.get("/api/items/$sharedItemId/transfers") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, transfersResponse.status)
        val transfersBody = Json.parseToJsonElement(transfersResponse.bodyAsText()).jsonObject
        assertEquals(2, transfersBody["total"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `storage audit session can be created updated and completed`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createItemResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Kirvis", "type": "COLLECTIVE", "category": "TOOLS", "quantity": 1 }""")
        }
        val itemId = Json.parseToJsonElement(createItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val createSecondItemResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Pjūklas", "type": "COLLECTIVE", "category": "TOOLS", "quantity": 2 }""")
        }
        val secondItemId = Json.parseToJsonElement(createSecondItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val createSessionResponse = client.post("/api/items/audit-sessions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "type": "COLLECTIVE", "category": "TOOLS", "sharedOnly": true }""")
        }
        assertEquals(HttpStatusCode.Created, createSessionResponse.status)
        val sessionBody = Json.parseToJsonElement(createSessionResponse.bodyAsText()).jsonObject
        val sessionId = sessionBody["id"]!!.jsonPrimitive.content
        assertEquals("STORAGE_AUDIT", sessionBody["contextType"]!!.jsonPrimitive.content)

        val saveChecksResponse = client.post("/api/items/audit-sessions/$sessionId/checks") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "checks": [
                        { "itemId": "$itemId", "result": "FOUND" }
                    ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, saveChecksResponse.status)
        val savedBody = Json.parseToJsonElement(saveChecksResponse.bodyAsText()).jsonObject
        assertEquals(1, savedBody["checks"]!!.jsonArray.size)
        assertEquals(1, savedBody["summary"]!!.jsonObject["checked"]!!.jsonPrimitive.content.toInt())

        val fetchSessionResponse = client.get("/api/items/audit-sessions/$sessionId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, fetchSessionResponse.status)
        val fetchedBody = Json.parseToJsonElement(fetchSessionResponse.bodyAsText()).jsonObject
        assertEquals(sessionId, fetchedBody["id"]!!.jsonPrimitive.content)
        assertEquals(1, fetchedBody["checks"]!!.jsonArray.size)

        val incompleteCompleteResponse = client.post("/api/items/audit-sessions/$sessionId/complete") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, incompleteCompleteResponse.status)

        val saveSecondCheckResponse = client.post("/api/items/audit-sessions/$sessionId/checks") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "checks": [
                        {
                            "itemId": "$secondItemId",
                            "result": "FOUND",
                            "actualQuantity": 3,
                            "conditionAtCheck": "NEEDS_INSPECTION"
                        }
                    ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, saveSecondCheckResponse.status)

        val completeResponse = client.post("/api/items/audit-sessions/$sessionId/complete") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, completeResponse.status)
        val completedBody = Json.parseToJsonElement(completeResponse.bodyAsText()).jsonObject
        assertEquals("COMPLETED", completedBody["status"]!!.jsonPrimitive.content)

        val updatedSecondItemResponse = client.get("/api/items/$secondItemId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, updatedSecondItemResponse.status)
        val updatedSecondItem = Json.parseToJsonElement(updatedSecondItemResponse.bodyAsText()).jsonObject
        assertEquals(3, updatedSecondItem["quantity"]!!.jsonPrimitive.content.toInt())
        assertEquals("NEEDS_INSPECTION", updatedSecondItem["condition"]!!.jsonPrimitive.content)
    }

    @Test
    fun `storage audit sessions can be resumed listed and locked after completion`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createItemResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Puodas", "type": "COLLECTIVE", "category": "COOKING", "quantity": 1 }""")
        }
        val itemId = Json.parseToJsonElement(createItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val firstSessionResponse = client.post("/api/items/audit-sessions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "type": "COLLECTIVE", "category": "COOKING", "sharedOnly": true }""")
        }
        assertEquals(HttpStatusCode.Created, firstSessionResponse.status)
        val firstSessionId = Json.parseToJsonElement(firstSessionResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val resumedSessionResponse = client.post("/api/items/audit-sessions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "type": "COLLECTIVE", "category": "COOKING", "sharedOnly": true }""")
        }
        assertEquals(HttpStatusCode.Created, resumedSessionResponse.status)
        val resumedSessionId = Json.parseToJsonElement(resumedSessionResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(firstSessionId, resumedSessionId)

        val openSessionsResponse = client.get("/api/items/audit-sessions?status=OPEN") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, openSessionsResponse.status)
        val openSessionsBody = Json.parseToJsonElement(openSessionsResponse.bodyAsText()).jsonObject
        assertEquals(1, openSessionsBody["total"]!!.jsonPrimitive.content.toInt())

        client.post("/api/items/audit-sessions/$firstSessionId/checks") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "checks": [{ "itemId": "$itemId", "result": "DAMAGED" }] }""")
        }
        val completeResponse = client.post("/api/items/audit-sessions/$firstSessionId/complete") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, completeResponse.status)

        val completedSessionsResponse = client.get("/api/items/audit-sessions?status=COMPLETED") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, completedSessionsResponse.status)
        val completedSessionsBody = Json.parseToJsonElement(completedSessionsResponse.bodyAsText()).jsonObject
        assertEquals(1, completedSessionsBody["total"]!!.jsonPrimitive.content.toInt())
        val completedSession = completedSessionsBody["sessions"]!!.jsonArray.first().jsonObject
        assertEquals(1, completedSession["summary"]!!.jsonObject["damaged"]!!.jsonPrimitive.content.toInt())

        val rejectedCheckResponse = client.post("/api/items/audit-sessions/$firstSessionId/checks") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "checks": [{ "itemId": "$itemId", "result": "FOUND" }] }""")
        }
        assertEquals(HttpStatusCode.BadRequest, rejectedCheckResponse.status)
    }
}
