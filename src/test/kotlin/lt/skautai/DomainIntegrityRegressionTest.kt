package lt.skautai

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.createUnit
import lt.skautai.TestHelper.randomEmail
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.TestHelper.registerInvitedUser
import lt.skautai.database.tables.*
import lt.skautai.models.requests.CreateEventInventoryMovementRequest
import lt.skautai.models.requests.CreateItemRequest
import lt.skautai.models.requests.ReservationMovementItemRequest
import lt.skautai.models.requests.ReservationMovementRequest
import lt.skautai.services.EventService
import lt.skautai.services.ItemService
import lt.skautai.services.ReservationService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PostgreSQL regressions for the production audit's approval and transaction failures. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DomainIntegrityRegressionTest {
    @Test fun `return cannot combine another inventory item with an existing custody`() = testApplication {
        configureFullApp()
        val email = randomEmail("custody-binding")
        val (_, tenant) = client.registerAndActivateTuntininkas(email = email)
        val tuntas = UUID.fromString(tenant)
        val owner = userId(email)
        val stock = item(tuntas, owner)
        val otherStock = item(tuntas, owner, name = "Other stock")
        val eventId = event(tuntas, owner)
        val service = EventService()
        val first = service.createInventoryMovement(eventId, tuntas, owner,
            CreateEventInventoryMovementRequest(stock.toString(), "CHECKOUT_TO_PERSON", 1), true).getOrThrow()
        assertTrue(service.createInventoryMovement(eventId, tuntas, owner,
            CreateEventInventoryMovementRequest(otherStock.toString(), "RETURN_TO_EVENT_STORAGE", 1,
                fromCustodyId = first.custodyId), true).isFailure)
        assertEquals(1L, transaction { EventInventoryItems.selectAll().count() })
        assertEquals(1L, transaction { EventInventoryMovements.selectAll().count() })
    }
    @Test fun `invalid later reconciliation line rolls back earlier return`() = testApplication {
        configureFullApp()
        val email = randomEmail("reconciliation")
        val (_, tenant) = client.registerAndActivateTuntininkas(email = email)
        val tuntas = UUID.fromString(tenant)
        val owner = userId(email)
        val stock = item(tuntas, owner, quantity = 2)
        val eventId = event(tuntas, owner)
        val service = EventService()
        val movement = service.createInventoryMovement(eventId, tuntas, owner,
            CreateEventInventoryMovementRequest(stock.toString(), "CHECKOUT_TO_PERSON", 1), true).getOrThrow()
        transaction { Events.update({ Events.id eq eventId }) { it[status] = "WRAP_UP" } }
        val valid = lt.skautai.models.requests.ReconcileEventReturnLineRequest(movement.custodyId!!, "RETURNED", 1)
        val before = transaction { EventInventoryMovements.selectAll().count() }
        val result = service.reconcileReturns(eventId, tuntas, owner,
            lt.skautai.models.requests.ReconcileEventReturnsRequest(listOf(valid, valid.copy(custodyId = "invalid"))))
        assertTrue(result.isFailure)
        assertEquals(before, transaction { EventInventoryMovements.selectAll().count() })
        assertEquals(0L, transaction { ItemChecks.selectAll().count() })
        assertTrue(service.reconcileReturns(eventId, tuntas, owner,
            lt.skautai.models.requests.ReconcileEventReturnsRequest(listOf(valid))).isSuccess)
    }
    @BeforeAll fun setup() = TestHelper.setupDatabase()
    @AfterAll fun teardown() = TestHelper.teardownDatabase()
    @BeforeEach fun clean() = TestHelper.cleanTables()

    private fun userId(email: String): UUID = transaction {
        Users.selectAll().where { Users.email eq email }.first()[Users.id]
    }

    private fun item(tuntasId: UUID, ownerId: UUID, name: String = "Audit stock", quantity: Int = 5, custodianId: String? = null): UUID =
        UUID.fromString(ItemService().createItem(tuntasId, ownerId, CreateItemRequest(
            name = name, type = "COLLECTIVE", category = "TOOLS", quantity = quantity, custodianId = custodianId
        )).getOrThrow().id)

    private fun event(tuntasId: UUID, ownerId: UUID): UUID = transaction {
        val now = Clock.System.now()
        Events.insert {
            it[Events.tuntasId] = tuntasId
            it[name] = "Audit active event"
            it[type] = "STOVYKLA"
            it[startDate] = LocalDate(2026, 9, 1)
            it[endDate] = LocalDate(2026, 9, 30)
            it[createdByUserId] = ownerId
            it[status] = "ACTIVE"
            it[createdAt] = now
            it[updatedAt] = now
        } get Events.id
    }

    private fun reservation(tuntasId: UUID, ownerId: UUID, itemId: UUID): UUID = transaction {
        val groupId = UUID.randomUUID()
        val now = Clock.System.now()
        Reservations.insert {
            it[Reservations.groupId] = groupId
            it[title] = "Audit reservation"
            it[Reservations.itemId] = itemId
            it[Reservations.tuntasId] = tuntasId
            it[reservedByUserId] = ownerId
            it[quantity] = 5
            it[startDate] = LocalDate(2026, 9, 1)
            it[endDate] = LocalDate(2026, 9, 30)
            it[status] = "APPROVED"
            it[createdAt] = now
            it[updatedAt] = now
        }
        groupId
    }

    @Test fun `pending duplicate handling cannot mutate active stock`() = testApplication {
        configureFullApp()
        val email = randomEmail("approval")
        val (token, tenant) = client.registerAndActivateTuntininkas(email = email)
        val tuntas = UUID.fromString(tenant)
        val owner = userId(email)
        val stock = item(tuntas, owner)
        val (_, memberText) = client.registerInvitedUser(token, tenant, "Skautas", randomEmail("submitter"))
        val member = UUID.fromString(memberText)
        val request = CreateItemRequest(name = "Audit stock", type = "COLLECTIVE", category = "TOOLS", quantity = 3,
            duplicateHandling = "ADD_TO_EXISTING", duplicateTargetItemId = stock.toString())
        assertTrue(ItemService().createItem(tuntas, member, request, isPendingApproval = true).isFailure)
        assertTrue(ItemService().createItem(tuntas, member, request.copy(duplicateHandling = "ASK"), isPendingApproval = true).isFailure)
        val pending = ItemService().createItem(tuntas, member, request.copy(duplicateHandling = "CREATE_NEW"), isPendingApproval = true).getOrThrow()
        assertEquals("PENDING_APPROVAL", pending.status)
        assertEquals(5, transaction { Items.selectAll().where { Items.id eq stock }.first()[Items.quantity] })
        assertEquals(0L, transaction { ItemHistory.selectAll().where { ItemHistory.eventType eq "RESTOCKED" }.count() })
    }

    @Test fun `duplicate merge checks resolved target permission and transferred origin`() = testApplication {
        configureFullApp()
        val email = randomEmail("merge-manager")
        val (token, tenant) = client.registerAndActivateTuntininkas(email = email)
        val tuntas = UUID.fromString(tenant)
        val owner = userId(email)
        val unit = createUnit(token, tenant, "Audit unit")
        val (_, leaderText) = client.registerInvitedUser(token, tenant, "Draugininkas", randomEmail("unit-leader"), unit)
        val leader = UUID.fromString(leaderText)
        val stock = item(tuntas, owner, custodianId = unit)
        val request = CreateItemRequest(name = "Audit stock", type = "COLLECTIVE", category = "TOOLS", quantity = 2,
            custodianId = unit, duplicateHandling = "ADD_TO_EXISTING", duplicateTargetItemId = stock.toString())
        assertEquals(7, ItemService().createItem(tuntas, leader, request).getOrThrow().quantity)
        transaction { Items.update({ Items.id eq stock }) { it[origin] = "TRANSFERRED_FROM_TUNTAS" } }
        assertTrue(ItemService().createItem(tuntas, leader, request).isFailure)
        assertEquals(9, ItemService().createItem(tuntas, owner, request).getOrThrow().quantity)
        val (_, memberText) = client.registerInvitedUser(token, tenant, "Skautas", randomEmail("no-update"), unit)
        assertTrue(ItemService().createItem(tuntas, UUID.fromString(memberText), request).isFailure)
        assertEquals(9, transaction { Items.selectAll().where { Items.id eq stock }.first()[Items.quantity] })
    }

    @Test fun `unauthorized or invalid source movement leaves no event inventory`() = testApplication {
        configureFullApp()
        val email = randomEmail("event-rejection")
        val (token, tenant) = client.registerAndActivateTuntininkas(email = email)
        val tuntas = UUID.fromString(tenant)
        val owner = userId(email)
        val stock = item(tuntas, owner)
        val eventId = event(tuntas, owner)
        val (_, memberText) = client.registerInvitedUser(token, tenant, "Skautas", randomEmail("event-member"))
        val service = EventService()
        val request = CreateEventInventoryMovementRequest(stock.toString(), "ASSIGN_TO_PASTOVYKLE", 1)
        assertTrue(service.createInventoryMovement(eventId, tuntas, UUID.fromString(memberText), request, false).isFailure)
        // This failure occurs after fallback materialization and must roll it back.
        assertTrue(service.createInventoryMovement(eventId, tuntas, owner, request.copy(pastovykleId = "invalid"), true).isFailure)
        assertEquals(0L, transaction { EventInventoryItems.selectAll().count() })
        assertEquals(0L, transaction { EventInventoryBuckets.selectAll().count() })
        assertEquals(0L, transaction { EventInventoryCustody.selectAll().count() })
        assertEquals(0L, transaction { EventInventoryMovements.selectAll().count() })
    }

    @Test fun `source movements reuse stock and retries cannot materialize unrelated source`() = testApplication {
        configureFullApp()
        val email = randomEmail("event-source")
        val (_, tenant) = client.registerAndActivateTuntininkas(email = email)
        val tuntas = UUID.fromString(tenant)
        val owner = userId(email)
        val stock = item(tuntas, owner, quantity = 2)
        val otherStock = item(tuntas, owner, name = "Other audit stock")
        val eventId = event(tuntas, owner)
        val service = EventService()
        val request = CreateEventInventoryMovementRequest(stock.toString(), "CHECKOUT_TO_PERSON", 1, requestId = UUID.randomUUID().toString())
        val first = service.createInventoryMovement(eventId, tuntas, owner, request, true).getOrThrow()
        assertEquals(first.id, service.createInventoryMovement(eventId, tuntas, owner, request, true).getOrThrow().id)
        for (altered in listOf(
            request.copy(notes = "Different payload"),
            request.copy(toUserId = UUID.randomUUID().toString()),
            request.copy(fromCustodyId = UUID.randomUUID().toString()),
            request.copy(pastovykleId = UUID.randomUUID().toString())
        )) {
            assertTrue(service.createInventoryMovement(eventId, tuntas, owner, altered, true).isFailure)
        }
        assertTrue(service.createInventoryMovement(eventId, tuntas, owner, request.copy(eventInventoryItemId = otherStock.toString()), true).isFailure)
        assertTrue(service.createInventoryMovement(eventId, tuntas, owner, request.copy(requestId = UUID.randomUUID().toString()), true).isSuccess)
        assertTrue(service.createInventoryMovement(eventId, tuntas, owner, request.copy(requestId = UUID.randomUUID().toString()), true).isFailure)
        assertEquals(1L, transaction { EventInventoryItems.selectAll().count() })
        assertEquals(2L, transaction { EventInventoryMovements.selectAll().count() })
        assertEquals(2, transaction { EventInventoryCustody.selectAll().sumOf { it[EventInventoryCustody.quantity] } })
    }

    @Test fun `reservation batch duplicates and a bad second item commit nothing`() = testApplication {
        configureFullApp()
        val email = randomEmail("reservation-batch")
        val (_, tenant) = client.registerAndActivateTuntininkas(email = email)
        val tuntas = UUID.fromString(tenant)
        val owner = userId(email)
        val stock = item(tuntas, owner)
        val group = reservation(tuntas, owner, stock)
        val service = ReservationService()
        val one = ReservationMovementItemRequest(stock.toString(), 5)
        val duplicate = ReservationMovementRequest(listOf(one, one.copy(itemId = stock.toString().uppercase())))
        assertTrue(service.recordMovement(group, tuntas, owner, "ISSUE", duplicate, true, emptySet()).isFailure)
        assertTrue(service.recordMovement(group, tuntas, owner, "ISSUE",
            ReservationMovementRequest(listOf(one, one.copy(itemId = UUID.randomUUID().toString()))), true, emptySet()).isFailure)
        assertEquals(0L, transaction { ReservationMovements.selectAll().count() })
        assertEquals(0L, transaction { ItemHistory.selectAll().where { ItemHistory.eventType eq "RESERVATION_ISSUED" }.count() })
        val request = ReservationMovementRequest(listOf(one))
        assertTrue(service.recordMovement(group, tuntas, owner, "ISSUE", request, true, emptySet()).isSuccess)
        assertTrue(service.recordMovement(group, tuntas, owner, "RETURN_MARKED", duplicate, false, emptySet()).isFailure)
        assertEquals(1L, transaction { ReservationMovements.selectAll().count() })
        assertTrue(service.recordMovement(group, tuntas, owner, "RETURN_MARKED", request, false, emptySet()).isSuccess)
        assertTrue(service.recordMovement(group, tuntas, owner, "RETURN", duplicate, true, emptySet()).isFailure)
        assertEquals("RETURNED", service.recordMovement(group, tuntas, owner, "RETURN", request, true, emptySet()).getOrThrow().status)
        assertEquals(3L, transaction { ReservationMovements.selectAll().count() })
    }

    @Test fun `concurrent reservation movements cannot exceed the reserved quantity`() = testApplication {
        configureFullApp()
        val email = randomEmail("reservation-concurrent")
        val (_, tenant) = client.registerAndActivateTuntininkas(email = email)
        val tuntas = UUID.fromString(tenant)
        val owner = userId(email)
        val stock = item(tuntas, owner)
        val group = reservation(tuntas, owner, stock)
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map {
                executor.submit(Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    ReservationService().recordMovement(group, tuntas, owner, "ISSUE",
                        ReservationMovementRequest(listOf(ReservationMovementItemRequest(stock.toString(), 5))), true, emptySet())
                })
            }
            val results = tasks.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(5, transaction { ReservationMovements.selectAll().sumOf { it[ReservationMovements.quantity] } })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `leave endpoint preserves the last leader and outstanding borrower membership`() = testApplication {
        configureFullApp()
        val email = randomEmail("leave-owner")
        val (token, tenant) = client.registerAndActivateTuntininkas(email = email)
        val tuntas = UUID.fromString(tenant)
        val owner = userId(email)
        val lastLeaderResponse = client.post("/api/users/me/tuntai/$tenant/leave") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, lastLeaderResponse.status)
        val (memberToken, memberText) = client.registerInvitedUser(token, tenant, "Skautas", randomEmail("borrower"))
        val member = UUID.fromString(memberText)
        val stock = item(tuntas, owner)
        val group = reservation(tuntas, member, stock)
        val borrowerResponse = client.post("/api/users/me/tuntai/$tenant/leave") { bearerAuth(memberToken) }
        assertEquals(HttpStatusCode.BadRequest, borrowerResponse.status)
        assertEquals(2L, transaction { UserTuntasMemberships.selectAll().where {
            (UserTuntasMemberships.tuntasId eq tuntas) and UserTuntasMemberships.leftAt.isNull()
        }.count() })
        transaction { Reservations.update({ Reservations.groupId eq group }) { it[status] = "CANCELLED" } }
        assertEquals(HttpStatusCode.OK, client.post("/api/users/me/tuntai/$tenant/leave") { bearerAuth(memberToken) }.status)
    }
}
