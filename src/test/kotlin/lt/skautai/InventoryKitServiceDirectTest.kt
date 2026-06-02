package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.createUnit
import lt.skautai.TestHelper.randomEmail
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.database.tables.InventoryKitItems
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateInventoryKitRequest
import lt.skautai.models.requests.CreateItemRequest
import lt.skautai.models.requests.CreateLocationRequest
import lt.skautai.models.requests.InventoryKitItemRequest
import lt.skautai.models.requests.UpdateInventoryKitRequest
import lt.skautai.services.InventoryKitService
import lt.skautai.services.ItemService
import lt.skautai.services.LocationService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryKitServiceDirectTest {

    private val kitService = InventoryKitService()
    private val itemService = ItemService()
    private val locationService = LocationService()

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

    private fun userIdByEmail(email: String): UUID = transaction {
        Users.selectAll()
            .where { Users.email eq email }
            .first()[Users.id]
    }

    private fun itemLocation(itemId: String): Pair<UUID?, String?> = transaction {
        Items.selectAll()
            .where { Items.id eq UUID.fromString(itemId) }
            .first()
            .let { it[Items.locationId] to it[Items.temporaryStorageLabel] }
    }

    private fun kitItemCount(kitId: String): Long = transaction {
        InventoryKitItems.selectAll()
            .where { InventoryKitItems.kitId eq UUID.fromString(kitId) }
            .count()
    }

    @Test
    fun `inventory kit service creates updates deactivates and syncs child items`() = testApplication {
        configureFullApp()
        val ownerEmail = randomEmail("kit-owner")
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val ownerId = userIdByEmail(ownerEmail)
        val unitId = createUnit(token, tuntasIdText, "Kit Unit")
        val root = locationService.createLocation(
            tuntasId,
            ownerId,
            CreateLocationRequest(name = "Sandelys", visibility = "PUBLIC")
        ).getOrThrow()
        val shelf = locationService.createLocation(
            tuntasId,
            ownerId,
            CreateLocationRequest(name = "Lentyna A", visibility = "PUBLIC", parentLocationId = root.id)
        ).getOrThrow()

        val pot = itemService.createItem(
            tuntasId,
            ownerId,
            CreateItemRequest(name = "Puodas", type = "COLLECTIVE", category = "COOKING", custodianId = unitId, quantity = 2)
        ).getOrThrow()
        val axe = itemService.createItem(
            tuntasId,
            ownerId,
            CreateItemRequest(name = "Kirvis", type = "COLLECTIVE", category = "TOOLS", custodianId = unitId, quantity = 1)
        ).getOrThrow()

        val created = kitService.createKit(
            tuntasId,
            ownerId,
            CreateInventoryKitRequest(
                name = " Virtuves rinkinys ",
                description = "  Puodai ir irankiai  ",
                custodianId = unitId,
                locationId = shelf.id,
                responsibleUserId = ownerId.toString(),
                temporaryStorageLabel = "  Dezes vidus  ",
                items = listOf(InventoryKitItemRequest(pot.id), InventoryKitItemRequest(axe.id))
            )
        ).getOrThrow()

        assertEquals("Virtuves rinkinys", created.name)
        assertEquals("Puodai ir irankiai", created.description)
        assertEquals(unitId, created.custodianId)
        assertEquals("Sandelys / Lentyna A", created.locationPath)
        assertEquals(ownerId.toString(), created.responsibleUserId)
        assertEquals(2, created.items.size)
        assertEquals(UUID.fromString(shelf.id), itemLocation(pot.id).first)
        assertEquals("Dezes vidus", itemLocation(axe.id).second)

        val listed = kitService.listKits(tuntasId).getOrThrow()
        assertEquals(1, listed.total)
        assertEquals(created.id, kitService.getKit(UUID.fromString(created.id), tuntasId).getOrThrow().id)

        val updated = kitService.updateKit(
            UUID.fromString(created.id),
            tuntasId,
            UpdateInventoryKitRequest(
                name = "Atnaujintas rinkinys",
                description = "   ",
                temporaryStorageLabel = "Laikina deze",
                clearLocationId = true,
                clearResponsibleUserId = true,
                items = listOf(InventoryKitItemRequest(pot.id))
            )
        ).getOrThrow()

        assertEquals("Atnaujintas rinkinys", updated.name)
        assertNull(updated.description)
        assertNull(updated.locationId)
        assertNull(updated.responsibleUserId)
        assertEquals(1, updated.items.size)
        assertNull(itemLocation(pot.id).first)
        assertEquals("Laikina deze", itemLocation(pot.id).second)
        assertEquals(1, kitItemCount(created.id))

        transaction {
            InventoryKitService.syncMembershipAfterItemQuantityChange(UUID.fromString(pot.id), 5)
        }
        val syncedQuantity = transaction {
            InventoryKitItems.selectAll()
                .where { InventoryKitItems.itemId eq UUID.fromString(pot.id) }
                .first()[InventoryKitItems.quantity]
        }
        assertEquals(5, syncedQuantity)

        val inactive = kitService.updateKit(
            UUID.fromString(created.id),
            tuntasId,
            UpdateInventoryKitRequest(status = "INACTIVE")
        ).getOrThrow()
        assertEquals("INACTIVE", inactive.status)
        assertEquals(0, kitItemCount(created.id))
        assertEquals(0, kitService.listKits(tuntasId).getOrThrow().total)
        assertEquals(1, kitService.listKits(tuntasId, includeInactive = true).getOrThrow().total)
    }

    @Test
    fun `inventory kit service validates invalid references scopes and active membership`() = testApplication {
        configureFullApp()
        val ownerEmail = randomEmail("kit-validation")
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val ownerId = userIdByEmail(ownerEmail)
        val unitA = createUnit(token, tuntasIdText, "Kit Unit A")
        val unitB = createUnit(token, tuntasIdText, "Kit Unit B")
        val first = itemService.createItem(
            tuntasId,
            ownerId,
            CreateItemRequest(name = "Pirmas", type = "COLLECTIVE", category = "TOOLS", custodianId = unitA, quantity = 1)
        ).getOrThrow()
        val second = itemService.createItem(
            tuntasId,
            ownerId,
            CreateItemRequest(name = "Antras", type = "COLLECTIVE", category = "TOOLS", custodianId = unitB, quantity = 1)
        ).getOrThrow()

        assertEquals(
            "Kit name is required",
            kitService.createKit(tuntasId, ownerId, CreateInventoryKitRequest(name = " ")).exceptionOrNull()?.message
        )
        assertEquals(
            "Kit name is too long",
            kitService.createKit(tuntasId, ownerId, CreateInventoryKitRequest(name = "A".repeat(201))).exceptionOrNull()?.message
        )
        assertEquals(
            "Invalid item ID",
            kitService.createKit(
                tuntasId,
                ownerId,
                CreateInventoryKitRequest(name = "Bad item", items = listOf(InventoryKitItemRequest("not-a-uuid")))
            ).exceptionOrNull()?.message
        )
        assertEquals(
            "Kit item not found in this tuntas",
            kitService.createKit(
                tuntasId,
                ownerId,
                CreateInventoryKitRequest(name = "Missing item", items = listOf(InventoryKitItemRequest(UUID.randomUUID().toString())))
            ).exceptionOrNull()?.message
        )
        assertEquals(
            "Kit items must belong to one inventory scope",
            kitService.createKit(
                tuntasId,
                ownerId,
                CreateInventoryKitRequest(
                    name = "Mixed scope",
                    items = listOf(InventoryKitItemRequest(first.id), InventoryKitItemRequest(second.id))
                )
            ).exceptionOrNull()?.message
        )
        assertEquals(
            "Invalid custodian ID",
            kitService.createKit(
                tuntasId,
                ownerId,
                CreateInventoryKitRequest(name = "Bad custodian", custodianId = "bad", items = listOf(InventoryKitItemRequest(first.id)))
            ).exceptionOrNull()?.message
        )
        assertEquals(
            "Kit custodian not found in this tuntas",
            kitService.createKit(
                tuntasId,
                ownerId,
                CreateInventoryKitRequest(
                    name = "Missing custodian",
                    custodianId = UUID.randomUUID().toString(),
                    items = listOf(InventoryKitItemRequest(first.id))
                )
            ).exceptionOrNull()?.message
        )
        assertEquals(
            "Kit custodian must match all child items",
            kitService.createKit(
                tuntasId,
                ownerId,
                CreateInventoryKitRequest(name = "Wrong custodian", custodianId = unitB, items = listOf(InventoryKitItemRequest(first.id)))
            ).exceptionOrNull()?.message
        )
        assertEquals(
            "Invalid location ID",
            kitService.createKit(
                tuntasId,
                ownerId,
                CreateInventoryKitRequest(name = "Bad location", locationId = "bad", items = listOf(InventoryKitItemRequest(first.id)))
            ).exceptionOrNull()?.message
        )
        assertEquals(
            "Responsible user not found",
            kitService.createKit(
                tuntasId,
                ownerId,
                CreateInventoryKitRequest(
                    name = "Bad responsible",
                    responsibleUserId = UUID.randomUUID().toString(),
                    items = listOf(InventoryKitItemRequest(first.id))
                )
            ).exceptionOrNull()?.message
        )

        val created = kitService.createKit(
            tuntasId,
            ownerId,
            CreateInventoryKitRequest(name = "First kit", items = listOf(InventoryKitItemRequest(first.id)))
        ).getOrThrow()
        assertEquals(
            "Pirmas is already in another active kit",
            kitService.createKit(
                tuntasId,
                ownerId,
                CreateInventoryKitRequest(name = "Duplicate kit", items = listOf(InventoryKitItemRequest(first.id)))
            ).exceptionOrNull()?.message
        )
        assertEquals(
            "Invalid kit status",
            kitService.updateKit(UUID.fromString(created.id), tuntasId, UpdateInventoryKitRequest(status = "ARCHIVED"))
                .exceptionOrNull()?.message
        )
        assertEquals(
            "Inventory kit not found",
            kitService.getKit(UUID.randomUUID(), tuntasId).exceptionOrNull()?.message
        )
        assertEquals(
            "Inventory kit not found",
            kitService.deleteKit(UUID.randomUUID(), tuntasId).exceptionOrNull()?.message
        )

        assertTrue(kitService.deleteKit(UUID.fromString(created.id), tuntasId).isSuccess)
        assertEquals(0, kitItemCount(created.id))
    }

    @Test
    fun `inventory kit routes expose crud and validate headers and ids`() = testApplication {
        configureFullApp()
        val ownerEmail = randomEmail("kit-route")
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val ownerId = userIdByEmail(ownerEmail)
        val item = itemService.createItem(
            tuntasId,
            ownerId,
            CreateItemRequest(name = "Marsrutinis kirvis", type = "COLLECTIVE", category = "TOOLS", quantity = 1)
        ).getOrThrow()

        val missingHeader = client.get("/api/inventory-kits") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, missingHeader.status)

        val invalidId = client.get("/api/inventory-kits/not-a-uuid") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
        }
        assertEquals(HttpStatusCode.BadRequest, invalidId.status)

        val createdResponse = client.post("/api/inventory-kits") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
            setBody("""{ "name": "Route kit", "items": [{ "itemId": "${item.id}" }] }""")
        }
        assertEquals(HttpStatusCode.Created, createdResponse.status)
        val created = Json.parseToJsonElement(createdResponse.bodyAsText()).jsonObject
        val kitId = created["id"]!!.jsonPrimitive.content
        assertNotNull(created["items"]!!.jsonArray.single())

        val listResponse = client.get("/api/inventory-kits") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
        }
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val listed = Json.parseToJsonElement(listResponse.bodyAsText()).jsonObject
        assertEquals("1", listed["total"]!!.jsonPrimitive.content)

        val getResponse = client.get("/api/inventory-kits/$kitId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
        }
        assertEquals(HttpStatusCode.OK, getResponse.status)

        val updateResponse = client.put("/api/inventory-kits/$kitId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
            setBody("""{ "name": "Route kit updated", "temporaryStorageLabel": "Test shelf" }""")
        }
        assertEquals(HttpStatusCode.OK, updateResponse.status)
        val updated = Json.parseToJsonElement(updateResponse.bodyAsText()).jsonObject
        assertEquals("Route kit updated", updated["name"]!!.jsonPrimitive.content)

        val deleteResponse = client.delete("/api/inventory-kits/$kitId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
        }
        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        val missingKit = client.get("/api/inventory-kits/${UUID.randomUUID()}") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
        }
        assertEquals(HttpStatusCode.NotFound, missingKit.status)
    }
}
