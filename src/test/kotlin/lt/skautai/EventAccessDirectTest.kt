package lt.skautai

import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.createUnit
import lt.skautai.TestHelper.getRoleId
import lt.skautai.TestHelper.randomEmail
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.TestHelper.registerInvitedUser
import lt.skautai.database.tables.EventRoles
import lt.skautai.database.tables.Pastovykles
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UserRanks
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.AssignPastovykleLeaderRequest
import lt.skautai.models.requests.CreateEventRequest
import lt.skautai.models.requests.CreatePastovykleRequest
import lt.skautai.services.EventService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
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
class EventAccessDirectTest {

    private val service = EventService()

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

    private fun insertRank(userId: UUID, tuntasId: UUID, roleName: String, assignedBy: UUID) {
        transaction {
            val roleId = Roles.selectAll()
                .where { (Roles.tuntasId eq tuntasId) and (Roles.name eq roleName) }
                .first()[Roles.id]
            UserRanks.deleteWhere { (UserRanks.userId eq userId) and (UserRanks.tuntasId eq tuntasId) }
            UserRanks.insert {
                it[this.userId] = userId
                it[this.roleId] = roleId
                it[this.tuntasId] = tuntasId
                it[assignedByUserId] = assignedBy
                it[assignedAt] = Clock.System.now()
            }
        }
    }

    @Test
    fun `event service evaluates create permissions and visible events by rank and unit`() = testApplication {
        configureFullApp()
        val ownerEmail = "event-owner@test.com"
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val ownerId = userIdByEmail(ownerEmail)

        val gildijaId = createUnit(token, tuntasIdText, "Gildija", "GILDIJA")
        val seniorUnitId = createUnit(token, tuntasIdText, "Vyrs", "VYR_SKAUTU_VIENETAS")

        val vadovasEmail = randomEmail("vadovas")
        val seniorEmail = randomEmail("senior")
        val (_, vadovasIdText) = client.registerInvitedUser(token, tuntasIdText, "Vadovas", vadovasEmail, gildijaId)
        val (_, seniorIdText) = client.registerInvitedUser(token, tuntasIdText, "Vyr. skautas", seniorEmail, seniorUnitId)
        val vadovasId = UUID.fromString(vadovasIdText)
        val seniorId = UUID.fromString(seniorIdText)

        insertRank(vadovasId, tuntasId, "Vadovas", ownerId)
        insertRank(seniorId, tuntasId, "Patyres skautas", ownerId)

        assertFalse(service.canCreateEvent(UUID.randomUUID(), tuntasId, null))
        assertTrue(service.canCreateEvent(vadovasId, tuntasId, null))
        assertTrue(service.canCreateEvent(vadovasId, tuntasId, UUID.fromString(gildijaId)))
        assertTrue(service.canCreateEvent(seniorId, tuntasId, UUID.fromString(seniorUnitId)))
        assertFalse(service.canCreateEvent(seniorId, tuntasId, UUID.fromString(gildijaId)))

        service.createEvent(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateEventRequest(
                name = "Bendras renginys",
                type = "STOVYKLA",
                startDate = "2026-07-01",
                endDate = "2026-07-05"
            )
        ).getOrThrow()

        service.createEvent(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateEventRequest(
                name = "Gildijos renginys",
                type = "SUEIGA",
                startDate = "2026-08-01",
                endDate = "2026-08-02",
                organizationalUnitId = gildijaId
            )
        ).getOrThrow()

        service.createEvent(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateEventRequest(
                name = "Senioru renginys",
                type = "RENGINYS",
                startDate = "2026-09-01",
                endDate = "2026-09-02",
                organizationalUnitId = seniorUnitId
            )
        ).getOrThrow()

        val vadovasVisible = service.getVisibleEvents(tuntasId, vadovasId).getOrThrow()
        val seniorVisible = service.getVisibleEvents(tuntasId, seniorId).getOrThrow()

        assertEquals(setOf("Bendras renginys", "Gildijos renginys"), vadovasVisible.events.map { it.name }.toSet())
        assertEquals(setOf("Senioru renginys"), seniorVisible.events.map { it.name }.toSet())
    }

    @Test
    fun `event service validates pastovyklės creation leader assignment and responsible visibility`() = testApplication {
        configureFullApp()
        val ownerEmail = "pastovykle-owner@test.com"
        val memberEmail = randomEmail("pastovykle-member")
        val outsiderEmail = "outsider@test.com"
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val (_, otherTuntasIdText) = client.registerAndActivateTuntininkas(email = outsiderEmail, tuntasName = "Kita stovykla")
        val tuntasId = UUID.fromString(tuntasIdText)
        val ownerId = userIdByEmail(ownerEmail)
        val outsiderId = userIdByEmail(outsiderEmail)
        val (_, memberIdText) = client.registerInvitedUser(token, tuntasIdText, "Skautas", memberEmail)
        val memberId = UUID.fromString(memberIdText)

        val event = service.createEvent(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateEventRequest(
                name = "Stovykla",
                type = "STOVYKLA",
                startDate = "2026-07-01",
                endDate = "2026-07-07"
            )
        ).getOrThrow()
        val eventId = UUID.fromString(event.id)

        val invalidAgeGroup = service.createPastovykle(
            eventId = eventId,
            tuntasId = tuntasId,
            request = CreatePastovykleRequest(name = "Vilkai", ageGroup = "BLOGAS")
        )
        assertTrue(invalidAgeGroup.isFailure)

        val outsiderResponsible = service.createPastovykle(
            eventId = eventId,
            tuntasId = tuntasId,
            request = CreatePastovykleRequest(name = "Skautai", responsibleUserId = outsiderId.toString())
        )
        assertEquals("Responsible user must be a member of this tuntas", outsiderResponsible.exceptionOrNull()?.message)

        val pastovykle = service.createPastovykle(
            eventId = eventId,
            tuntasId = tuntasId,
            request = CreatePastovykleRequest(name = "Pirma", responsibleUserId = memberId.toString(), ageGroup = "SKAUTAI")
        ).getOrThrow()
        val pastovykleId = UUID.fromString(pastovykle.id)

        val sameResponsibleAsLeader = service.assignPastovykleLeader(
            eventId = eventId,
            pastovykleId = pastovykleId,
            tuntasId = tuntasId,
            assignedByUserId = ownerId,
            request = AssignPastovykleLeaderRequest(userId = memberId.toString())
        )
        assertEquals(
            "Pagrindinis vadovas jau turi pastovyklės vadovo teises",
            sameResponsibleAsLeader.exceptionOrNull()?.message
        )

        val anotherEmail = randomEmail("pastovykle-leader")
        val (_, anotherIdText) = client.registerInvitedUser(token, tuntasIdText, "Skautas", anotherEmail)
        val anotherId = UUID.fromString(anotherIdText)

        val assigned = service.assignPastovykleLeader(
            eventId = eventId,
            pastovykleId = pastovykleId,
            tuntasId = tuntasId,
            assignedByUserId = ownerId,
            request = AssignPastovykleLeaderRequest(userId = anotherId.toString())
        )
        assertTrue(assigned.isSuccess)
        assertEquals("PASTOVYKLES_GURU", assigned.getOrThrow().role)

        val responsibleEvents = service.getResponsibleEvents(tuntasId, memberId).getOrThrow()
        assertEquals(listOf("Stovykla"), responsibleEvents.events.map { it.name })

        val roleRows = transaction {
            EventRoles.selectAll()
                .where { EventRoles.eventId eq eventId }
                .count()
        }
        val pastovykleRows = transaction {
            Pastovykles.selectAll()
                .where { Pastovykles.id eq pastovykleId }
                .count()
        }
        assertTrue(roleRows >= 2)
        assertEquals(1, pastovykleRows)
        assertTrue(otherTuntasIdText.isNotBlank())
    }
}
