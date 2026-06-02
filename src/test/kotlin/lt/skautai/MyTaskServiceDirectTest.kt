package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.createUnit
import lt.skautai.TestHelper.getRoleId
import lt.skautai.TestHelper.randomEmail
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.TestHelper.registerInvitedUser
import lt.skautai.database.tables.BendrasInventoryRequests
import lt.skautai.database.tables.DraugoveRequisitions
import lt.skautai.database.tables.EventInventoryCustody
import lt.skautai.database.tables.EventInventoryItems
import lt.skautai.database.tables.Events
import lt.skautai.database.tables.ItemCheckSessions
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.LeadershipChangeRequests
import lt.skautai.database.tables.ReservationMovements
import lt.skautai.database.tables.Reservations
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateEventRequest
import lt.skautai.models.requests.CreateItemRequest
import lt.skautai.services.EventService
import lt.skautai.services.ItemService
import lt.skautai.services.MyTaskService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyTaskServiceDirectTest {

    private val eventService = EventService()
    private val itemService = ItemService()
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

    @Test
    fun `top leader receives review return movement leadership and audit tasks`() = testApplication {
        configureFullApp()
        val leaderEmail = randomEmail("task-leader")
        val requesterEmail = randomEmail("task-requester")
        val unitLeaderEmail = randomEmail("task-unit-leader")
        val (leaderToken, tuntasIdText) = client.registerAndActivateTuntininkas(email = leaderEmail)
        val unitIdText = createUnit(leaderToken, tuntasIdText, "Tasks Unit")
        val (_, requesterIdText) = client.registerInvitedUser(
            inviterToken = leaderToken,
            tuntasId = tuntasIdText,
            roleName = "Skautas",
            email = requesterEmail
        )
        val (_, unitLeaderIdText) = client.registerInvitedUser(
            inviterToken = leaderToken,
            tuntasId = tuntasIdText,
            roleName = "Draugininkas",
            email = unitLeaderEmail,
            organizationalUnitId = unitIdText
        )
        val tuntasId = UUID.fromString(tuntasIdText)
        val unitId = UUID.fromString(unitIdText)
        val leaderId = userIdByEmail(leaderEmail)
        val requesterId = UUID.fromString(requesterIdText)
        val unitLeaderId = UUID.fromString(unitLeaderIdText)
        val now = Clock.System.now()
        val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date

        itemService.createItem(
            tuntasId = tuntasId,
            createdByUserId = requesterId,
            request = CreateItemRequest(name = "Pending item", type = "COLLECTIVE", category = "TOOLS", quantity = 1),
            isPendingApproval = true
        ).getOrThrow()
        val sharedItem = itemService.createItem(
            tuntasId = tuntasId,
            createdByUserId = leaderId,
            request = CreateItemRequest(name = "Shared item", type = "COLLECTIVE", category = "TOOLS", quantity = 3)
        ).getOrThrow()
        val sharedItemId = UUID.fromString(sharedItem.id)
        val groupForApproval = UUID.randomUUID()
        val groupForReturn = UUID.randomUUID()
        val groupForMovement = UUID.randomUUID()

        transaction {
            Reservations.insert {
                it[groupId] = groupForApproval
                it[title] = "Needs approval"
                it[itemId] = sharedItemId
                it[this.tuntasId] = tuntasId
                it[reservedByUserId] = requesterId
                it[quantity] = 1
                it[startDate] = LocalDate.parse("2026-07-01")
                it[endDate] = LocalDate.parse("2026-07-02")
                it[topLevelReviewStatus] = "PENDING"
                it[status] = "PENDING"
                it[createdAt] = now
                it[updatedAt] = now
            }
            Reservations.insert {
                it[groupId] = groupForReturn
                it[title] = "My overdue return"
                it[itemId] = sharedItemId
                it[this.tuntasId] = tuntasId
                it[reservedByUserId] = leaderId
                it[quantity] = 1
                it[startDate] = LocalDate.parse("2020-01-01")
                it[endDate] = LocalDate.parse("2020-01-02")
                it[status] = "ACTIVE"
                it[createdAt] = now
                it[updatedAt] = now
            }
            ReservationMovements.insert {
                it[reservationGroupId] = groupForReturn
                it[itemId] = sharedItemId
                it[type] = "ISSUE"
                it[quantity] = 1
                it[performedByUserId] = leaderId
                it[createdAt] = now
            }
            Reservations.insert {
                it[groupId] = groupForMovement
                it[title] = "Open movement"
                it[itemId] = sharedItemId
                it[this.tuntasId] = tuntasId
                it[reservedByUserId] = requesterId
                it[quantity] = 1
                it[startDate] = today
                it[endDate] = today
                it[pickupAt] = now
                it[status] = "APPROVED"
                it[createdAt] = now
                it[updatedAt] = now
            }
            DraugoveRequisitions.insert {
                it[this.tuntasId] = tuntasId
                it[organizationalUnitId] = unitId
                it[createdByUserId] = requesterId
                it[status] = "SUBMITTED"
                it[unitReviewStatus] = "APPROVED"
                it[topLevelReviewStatus] = "PENDING"
                it[createdAt] = now
                it[updatedAt] = now
            }
            BendrasInventoryRequests.insert {
                it[this.tuntasId] = tuntasId
                it[requestedByUserId] = requesterId
                it[itemDescription] = "Bendro inventoriaus prasymas"
                it[quantity] = 1
                it[requestingUnitId] = unitId
                it[topLevelStatus] = "PENDING"
                it[createdAt] = now
                it[updatedAt] = now
            }
            val unitLeaderRoleId = UUID.fromString(getRoleId(tuntasIdText, "Draugininkas"))
            val assignmentId = UserLeadershipRoles.selectAll()
                .where {
                    (UserLeadershipRoles.userId eq unitLeaderId) and
                        (UserLeadershipRoles.roleId eq unitLeaderRoleId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId)
                }
                .first()[UserLeadershipRoles.id]
            LeadershipChangeRequests.insert {
                it[this.tuntasId] = tuntasId
                it[requesterUserId] = unitLeaderId
                it[roleAssignmentId] = assignmentId
                it[roleId] = unitLeaderRoleId
                it[organizationalUnitId] = unitId
                it[status] = "PENDING"
                it[reason] = "Kadencijos pabaiga"
                it[createdAt] = now
                it[updatedAt] = now
            }
            ItemCheckSessions.insert {
                it[this.tuntasId] = tuntasId
                it[contextType] = "STORAGE_AUDIT"
                it[startedByUserId] = requesterId
                it[status] = "OPEN"
                it[scopeItemCount] = 1
                it[createdAt] = now
            }
        }

        val tasks = taskService.getMyTasks(tuntasId, leaderId).getOrThrow().tasks
        val types = tasks.map { it.type }.toSet()

        assertTrue("INVENTORY_APPROVAL_PENDING" in types)
        assertTrue("RESERVATION_APPROVAL_PENDING" in types)
        assertTrue("MY_RETURN_OVERDUE" in types)
        assertTrue("RESERVATION_MOVEMENT_OPEN" in types)
        assertTrue("REQUISITION_REVIEW_PENDING" in types)
        assertTrue("SHARED_PICKUP_REVIEW_PENDING" in types)
        assertTrue("LEADERSHIP_CHANGE_REVIEW_PENDING" in types)
        assertTrue("AUDIT_SESSION_OPEN" in types)
        assertEquals("MY_RETURN_OVERDUE", tasks.first().type)
    }

    @Test
    fun `my task route validates tuntas header and returns user tasks`() = testApplication {
        configureFullApp()
        val leaderEmail = randomEmail("task-route")
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = leaderEmail)
        val missingHeader = client.get("/api/tasks/my") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, missingHeader.status)

        val invalidHeader = client.get("/api/tasks/my") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", "not-a-uuid")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidHeader.status)

        val ok = client.get("/api/tasks/my") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("\"tasks\""))
    }
}
