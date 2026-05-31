package lt.skautai

import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.randomEmail
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.TestHelper.registerInvitedUser
import lt.skautai.database.tables.EventInventoryCustody
import lt.skautai.database.tables.EventInventoryItems
import lt.skautai.database.tables.Events
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateEventRequest
import lt.skautai.services.EventService
import lt.skautai.services.MyTaskService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyTaskServiceDirectTest {

    private val eventService = EventService()
    private val taskService = MyTaskService()

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

    @Test
    fun `tuntininkas without event role does not receive actionable event tasks`() = testApplication {
        configureFullApp()
        val ownerEmail = randomEmail("task-tuntininkas")
        val (tuntininkasToken, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val tuntininkasId = userIdByEmail(ownerEmail)
        val (_, virsininkasIdText) = client.registerInvitedUser(
            inviterToken = tuntininkasToken,
            tuntasId = tuntasIdText,
            roleName = "Vadovas",
            email = randomEmail("task-virsininkas")
        )
        val virsininkasId = UUID.fromString(virsininkasIdText)

        val event = eventService.createEvent(
            tuntasId = tuntasId,
            createdByUserId = virsininkasId,
            request = CreateEventRequest(
                name = "Renginys su nebaigtu inventoriumi",
                type = "STOVYKLA",
                startDate = "2026-07-01",
                endDate = "2026-07-07"
            )
        ).getOrThrow()
        val eventId = UUID.fromString(event.id)

        val eventInventoryItemId = transaction {
            Events.update({ Events.id eq eventId }) {
                it[status] = "WRAP_UP"
            }
            val itemId = EventInventoryItems.insert {
                it[this.eventId] = eventId
                it[name] = "Palapine"
                it[plannedQuantity] = 2
                it[availableQuantity] = 0
                it[needsPurchase] = true
                it[createdByUserId] = virsininkasId
                it[createdAt] = Clock.System.now()
            } get EventInventoryItems.id
            EventInventoryCustody.insert {
                it[eventInventoryItemId] = itemId
                it[quantity] = 1
                it[returnedQuantity] = 0
                it[status] = "OPEN"
                it[createdByUserId] = virsininkasId
                it[createdAt] = Clock.System.now()
            }
            itemId
        }

        val tuntininkasTasks = taskService.getMyTasks(tuntasId, tuntininkasId).getOrThrow().tasks
        val virsininkasTasks = taskService.getMyTasks(tuntasId, virsininkasId).getOrThrow().tasks

        assertFalse(tuntininkasTasks.any { it.type == "EVENT_LOGISTICS_OPEN" })
        assertFalse(tuntininkasTasks.any { it.type == "EVENT_RECONCILIATION_OPEN" })
        assertTrue(virsininkasTasks.any { it.type == "EVENT_LOGISTICS_OPEN" })
        assertTrue(virsininkasTasks.any { it.type == "EVENT_RECONCILIATION_OPEN" })
        assertTrue(eventInventoryItemId.toString().isNotBlank())
    }
}
