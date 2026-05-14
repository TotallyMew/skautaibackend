package lt.skautai

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.createUnit
import lt.skautai.TestHelper.randomEmail
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.TestHelper.registerInvitedUser
import lt.skautai.database.tables.EventInventoryBuckets
import lt.skautai.database.tables.Events
import lt.skautai.database.tables.ReservationMovements
import lt.skautai.database.tables.Reservations
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateLocationRequest
import lt.skautai.models.requests.UpdateLocationRequest
import lt.skautai.services.LocationService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocationServiceDirectTest {

    private val service = LocationService()

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

    private fun userIdForEmail(email: String): UUID = transaction {
        Users.selectAll()
            .where { Users.email eq email }
            .first()[Users.id]
    }

    private suspend fun HttpClient.createItem(token: String, tuntasId: String, name: String, locationId: String? = null): String {
        val locationField = locationId?.let { """, "locationId": "$it"""" }.orEmpty()
        val response = post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "type": "COLLECTIVE", "category": "TOOLS", "quantity": 1$locationField }""")
        }
        check(response.status == HttpStatusCode.Created) {
            "Failed to create item: ${response.status}"
        }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `location service validates create visibility duplicates and parent trees`() = testApplication {
        configureFullApp()
        val leaderEmail = randomEmail("location-leader")
        val memberEmail = randomEmail("location-member")
        val otherMemberEmail = randomEmail("location-other")
        val (leaderToken, tuntasIdText) = client.registerAndActivateTuntininkas(email = leaderEmail)
        val (_, memberIdText) = client.registerInvitedUser(leaderToken, tuntasIdText, "Skautas", memberEmail)
        val (_, otherMemberIdText) = client.registerInvitedUser(leaderToken, tuntasIdText, "Skautas", otherMemberEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val leaderId = userIdForEmail(leaderEmail)
        val memberId = UUID.fromString(memberIdText)
        val otherMemberId = UUID.fromString(otherMemberIdText)

        val publicRoot = service.createLocation(
            tuntasId,
            leaderId,
            CreateLocationRequest(name = "Main Hall", visibility = "PUBLIC")
        ).getOrThrow()

        val memberPublic = service.createLocation(
            tuntasId,
            memberId,
            CreateLocationRequest(name = "Members Public", visibility = "PUBLIC")
        )
        assertEquals(
            "Only tuntas leaders and inventory managers can create public locations",
            memberPublic.exceptionOrNull()?.message
        )

        val memberUnit = service.createLocation(
            tuntasId,
            memberId,
            CreateLocationRequest(name = "Unit Shelf", visibility = "UNIT")
        )
        assertEquals("Only leaders can create unit locations", memberUnit.exceptionOrNull()?.message)

        val privateRoot = service.createLocation(
            tuntasId,
            memberId,
            CreateLocationRequest(name = "Locker", visibility = "PRIVATE")
        )
        assertTrue(privateRoot.isSuccess)

        val duplicatePrivate = service.createLocation(
            tuntasId,
            memberId,
            CreateLocationRequest(name = "Locker", visibility = "PRIVATE")
        )
        assertEquals("Location with this name already exists in the same folder", duplicatePrivate.exceptionOrNull()?.message)

        val otherPrivateRoot = service.createLocation(
            tuntasId,
            otherMemberId,
            CreateLocationRequest(name = "Other Locker", visibility = "PRIVATE")
        ).getOrThrow()

        val visibilityMismatch = service.createLocation(
            tuntasId,
            memberId,
            CreateLocationRequest(
                name = "Hidden Under Public",
                visibility = "PRIVATE",
                parentLocationId = publicRoot.id
            )
        )
        assertEquals("Parent location visibility must match child visibility", visibilityMismatch.exceptionOrNull()?.message)

        val wrongPrivateTree = service.createLocation(
            tuntasId,
            memberId,
            CreateLocationRequest(
                name = "Foreign Child",
                visibility = "PRIVATE",
                parentLocationId = otherPrivateRoot.id
            )
        )
        assertEquals("Private sublocation must stay inside the same private tree", wrongPrivateTree.exceptionOrNull()?.message)
    }

    @Test
    fun `location service validates update scope and hierarchy rules`() = testApplication {
        configureFullApp()
        val leaderEmail = randomEmail("location-top")
        val unitLeaderEmail = randomEmail("location-unit-leader")
        val (leaderToken, tuntasIdText) = client.registerAndActivateTuntininkas(email = leaderEmail)
        val unitA = createUnit(leaderToken, tuntasIdText, "Skautai A")
        val unitB = createUnit(leaderToken, tuntasIdText, "Skautai B")
        val (_, unitLeaderIdText) = client.registerInvitedUser(leaderToken, tuntasIdText, "Draugininkas", unitLeaderEmail, unitA)
        val tuntasId = UUID.fromString(tuntasIdText)
        val unitLeaderId = UUID.fromString(unitLeaderIdText)

        val parent = service.createLocation(
            tuntasId,
            unitLeaderId,
            CreateLocationRequest(name = "Unit Root", visibility = "UNIT", ownerUnitId = unitA)
        ).getOrThrow()
        val child = service.createLocation(
            tuntasId,
            unitLeaderId,
            CreateLocationRequest(
                name = "Unit Child",
                visibility = "UNIT",
                ownerUnitId = unitA,
                parentLocationId = parent.id
            )
        ).getOrThrow()

        val makePublic = service.updateLocation(
            UUID.fromString(parent.id),
            tuntasId,
            unitLeaderId,
            UpdateLocationRequest(visibility = "PUBLIC")
        )
        assertEquals(
            "Only tuntas leaders and inventory managers can manage public locations",
            makePublic.exceptionOrNull()?.message
        )

        val moveToOtherUnit = service.updateLocation(
            UUID.fromString(parent.id),
            tuntasId,
            unitLeaderId,
            UpdateLocationRequest(ownerUnitId = unitB)
        )
        assertEquals("You can manage unit locations only for your own units", moveToOtherUnit.exceptionOrNull()?.message)

        val selfParent = service.updateLocation(
            UUID.fromString(parent.id),
            tuntasId,
            unitLeaderId,
            UpdateLocationRequest(parentLocationId = parent.id)
        )
        assertEquals("Location cannot be its own parent", selfParent.exceptionOrNull()?.message)

        val underDescendant = service.updateLocation(
            UUID.fromString(parent.id),
            tuntasId,
            unitLeaderId,
            UpdateLocationRequest(parentLocationId = child.id)
        )
        assertEquals("Location cannot be moved under its own descendant", underDescendant.exceptionOrNull()?.message)
    }

    @Test
    fun `location service delete blockers cover active references`() = testApplication {
        configureFullApp()
        val leaderEmail = randomEmail("location-delete")
        val (leaderToken, tuntasIdText) = client.registerAndActivateTuntininkas(email = leaderEmail)
        val leaderId = userIdForEmail(leaderEmail)
        val tuntasId = UUID.fromString(tuntasIdText)

        val parent = service.createLocation(
            tuntasId,
            leaderId,
            CreateLocationRequest(name = "Parent", visibility = "PUBLIC")
        ).getOrThrow()
        service.createLocation(
            tuntasId,
            leaderId,
            CreateLocationRequest(name = "Child", visibility = "PUBLIC", parentLocationId = parent.id)
        ).getOrThrow()
        assertEquals(
            "Cannot delete location that still has sublocations",
            service.deleteLocation(UUID.fromString(parent.id), tuntasId, leaderId).exceptionOrNull()?.message
        )

        val itemLocation = service.createLocation(
            tuntasId,
            leaderId,
            CreateLocationRequest(name = "Item Spot", visibility = "PUBLIC")
        ).getOrThrow()
        val looseItemId = UUID.fromString(client.createItem(leaderToken, tuntasIdText, "Loose Axe"))
        client.createItem(leaderToken, tuntasIdText, "Anchored Axe", itemLocation.id)
        assertEquals(
            "Cannot delete location that has active items assigned to it",
            service.deleteLocation(UUID.fromString(itemLocation.id), tuntasId, leaderId).exceptionOrNull()?.message
        )

        val pickupLocation = service.createLocation(
            tuntasId,
            leaderId,
            CreateLocationRequest(name = "Pickup Spot", visibility = "PUBLIC")
        ).getOrThrow()
        transaction {
            Reservations.insert {
                it[groupId] = UUID.randomUUID()
                it[title] = "Pickup reservation"
                it[itemId] = looseItemId
                it[this.tuntasId] = tuntasId
                it[reservedByUserId] = leaderId
                it[quantity] = 1
                it[startDate] = LocalDate.parse("2026-08-01")
                it[endDate] = LocalDate.parse("2026-08-02")
                it[pickupLocationId] = UUID.fromString(pickupLocation.id)
                it[status] = "APPROVED"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }
        assertEquals(
            "Cannot delete location used for reservation pickup",
            service.deleteLocation(UUID.fromString(pickupLocation.id), tuntasId, leaderId).exceptionOrNull()?.message
        )

        val returnLocation = service.createLocation(
            tuntasId,
            leaderId,
            CreateLocationRequest(name = "Return Spot", visibility = "PUBLIC")
        ).getOrThrow()
        transaction {
            Reservations.insert {
                it[groupId] = UUID.randomUUID()
                it[title] = "Return reservation"
                it[itemId] = looseItemId
                it[this.tuntasId] = tuntasId
                it[reservedByUserId] = leaderId
                it[quantity] = 1
                it[startDate] = LocalDate.parse("2026-08-03")
                it[endDate] = LocalDate.parse("2026-08-04")
                it[returnLocationId] = UUID.fromString(returnLocation.id)
                it[status] = "APPROVED"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }
        assertEquals(
            "Cannot delete location used for reservation return",
            service.deleteLocation(UUID.fromString(returnLocation.id), tuntasId, leaderId).exceptionOrNull()?.message
        )

        val movementLocation = service.createLocation(
            tuntasId,
            leaderId,
            CreateLocationRequest(name = "Movement Spot", visibility = "PUBLIC")
        ).getOrThrow()
        transaction {
            ReservationMovements.insert {
                it[reservationGroupId] = UUID.randomUUID()
                it[itemId] = looseItemId
                it[locationId] = UUID.fromString(movementLocation.id)
                it[type] = "ISSUE"
                it[quantity] = 1
                it[performedByUserId] = leaderId
                it[createdAt] = Clock.System.now()
            }
        }
        assertEquals(
            "Cannot delete location used in reservation movements",
            service.deleteLocation(UUID.fromString(movementLocation.id), tuntasId, leaderId).exceptionOrNull()?.message
        )

        val eventBucketLocation = service.createLocation(
            tuntasId,
            leaderId,
            CreateLocationRequest(name = "Bucket Spot", visibility = "PUBLIC")
        ).getOrThrow()
        val bucketEventId = transaction {
            Events.insert {
                it[this.tuntasId] = tuntasId
                it[name] = "Bucket Event"
                it[type] = "RENGINYS"
                it[startDate] = LocalDate.parse("2026-09-01")
                it[endDate] = LocalDate.parse("2026-09-02")
                it[createdByUserId] = leaderId
                it[status] = "PLANNING"
                it[createdAt] = Clock.System.now()
            } get Events.id
        }
        transaction {
            EventInventoryBuckets.insert {
                it[eventId] = bucketEventId
                it[locationId] = UUID.fromString(eventBucketLocation.id)
                it[name] = "Bucket"
                it[type] = "OTHER"
            }
        }
        assertEquals(
            "Cannot delete location used by event inventory",
            service.deleteLocation(UUID.fromString(eventBucketLocation.id), tuntasId, leaderId).exceptionOrNull()?.message
        )

        val eventLocation = service.createLocation(
            tuntasId,
            leaderId,
            CreateLocationRequest(name = "Event Spot", visibility = "PUBLIC")
        ).getOrThrow()
        transaction {
            Events.insert {
                it[this.tuntasId] = tuntasId
                it[name] = "Assigned Event"
                it[type] = "STOVYKLA"
                it[startDate] = LocalDate.parse("2026-10-01")
                it[endDate] = LocalDate.parse("2026-10-05")
                it[locationId] = UUID.fromString(eventLocation.id)
                it[createdByUserId] = leaderId
                it[status] = "PLANNING"
                it[createdAt] = Clock.System.now()
            }
        }
        assertEquals(
            "Cannot delete location assigned to an event",
            service.deleteLocation(UUID.fromString(eventLocation.id), tuntasId, leaderId).exceptionOrNull()?.message
        )
    }
}
