package lt.skautai

import io.ktor.server.testing.testApplication
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.createUnit
import lt.skautai.TestHelper.getRoleId
import lt.skautai.TestHelper.randomEmail
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.TestHelper.registerInvitedUser
import lt.skautai.models.requests.AssignLeadershipRoleRequest
import lt.skautai.models.requests.CreateLeadershipChangeRequest
import lt.skautai.models.requests.ReviewLeadershipChangeRequest
import lt.skautai.models.requests.UpdateLeadershipRoleRequest
import lt.skautai.services.LeadershipChangeRequestService
import lt.skautai.services.MemberService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import lt.skautai.database.tables.LeadershipChangeRequests
import lt.skautai.database.tables.Users
import lt.skautai.database.tables.UserLeadershipRoles
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberServiceDirectTest {

    private val service = MemberService()
    private val leadershipChangeRequestService = LeadershipChangeRequestService()

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
    fun `member service rejects non member callers and inaccessible member lookups`() = testApplication {
        configureFullApp()
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas()
        val unitA = createUnit(token, tuntasIdText, "Unit A")
        val unitB = createUnit(token, tuntasIdText, "Unit B")
        val (_, leaderAIdText) = client.registerInvitedUser(token, tuntasIdText, "Draugininkas", randomEmail("leader-a"), unitA)
        val (_, memberBIdText) = client.registerInvitedUser(token, tuntasIdText, "Skautas", randomEmail("member-b"), unitB)
        val tuntasId = UUID.fromString(tuntasIdText)

        val membersFailure = service.getMembers(tuntasId, UUID.randomUUID())
        assertTrue(membersFailure.isFailure)
        assertEquals("You are not an active member of this tuntas", membersFailure.exceptionOrNull()?.message)

        val detailResult = service.getMember(
            userId = UUID.fromString(memberBIdText),
            tuntasId = tuntasId,
            callerUserId = UUID.fromString(leaderAIdText)
        )
        assertTrue(detailResult.isSuccess)
        assertEquals(memberBIdText, detailResult.getOrNull()?.userId)
    }

    @Test
    fun `assign leadership role validates role unit and date inputs`() = testApplication {
        configureFullApp()
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas()
        val tuntasId = UUID.fromString(tuntasIdText)
        val unitId = createUnit(token, tuntasIdText, "Leadership unit")
        val (_, memberIdText) = client.registerInvitedUser(token, tuntasIdText, "Skautas", randomEmail("lead-target"))
        val memberId = UUID.fromString(memberIdText)
        val draugininkasRoleId = getRoleId(tuntasIdText, "Draugininkas")
        val tuntininkasRoleId = getRoleId(tuntasIdText, "Tuntininkas")

        val invalidRole = service.assignLeadershipRole(
            memberId,
            tuntasId,
            null,
            AssignLeadershipRoleRequest(roleId = "not-a-uuid")
        )
        assertEquals("Invalid role ID", invalidRole.exceptionOrNull()?.message)

        val transferOnly = service.assignLeadershipRole(
            memberId,
            tuntasId,
            null,
            AssignLeadershipRoleRequest(roleId = tuntininkasRoleId)
        )
        assertEquals("Tuntininkas role can only be transferred", transferOnly.exceptionOrNull()?.message)

        val invalidUnit = service.assignLeadershipRole(
            memberId,
            tuntasId,
            null,
            AssignLeadershipRoleRequest(roleId = draugininkasRoleId, organizationalUnitId = "not-a-uuid")
        )
        assertEquals("Invalid organizational unit ID", invalidUnit.exceptionOrNull()?.message)

        val missingUnit = service.assignLeadershipRole(
            memberId,
            tuntasId,
            null,
            AssignLeadershipRoleRequest(roleId = draugininkasRoleId, organizationalUnitId = UUID.randomUUID().toString())
        )
        assertEquals("Organizational unit not found in this tuntas", missingUnit.exceptionOrNull()?.message)

        val badStart = service.assignLeadershipRole(
            memberId,
            tuntasId,
            null,
            AssignLeadershipRoleRequest(roleId = draugininkasRoleId, organizationalUnitId = unitId, startsAt = "2026/01/01")
        )
        assertEquals("Invalid startsAt format, use ISO 8601", badStart.exceptionOrNull()?.message)

        val badExpiry = service.assignLeadershipRole(
            memberId,
            tuntasId,
            null,
            AssignLeadershipRoleRequest(roleId = draugininkasRoleId, organizationalUnitId = unitId, expiresAt = "2026/01/01")
        )
        assertEquals("Invalid expiresAt format, use ISO 8601", badExpiry.exceptionOrNull()?.message)
    }

    @Test
    fun `update and remove leadership role validate assignment caller and status rules`() = testApplication {
        configureFullApp()
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasIdText, "Update Unit")
        val (_, memberIdText) = client.registerInvitedUser(token, tuntasIdText, "Skautas", randomEmail("update-target"))
        val memberId = UUID.fromString(memberIdText)
        val tuntasId = UUID.fromString(tuntasIdText)
        val roleId = getRoleId(tuntasIdText, "Draugininkas")

        val created = service.assignLeadershipRole(
            memberId,
            tuntasId,
            null,
            AssignLeadershipRoleRequest(roleId = roleId, organizationalUnitId = unitId)
        ).getOrThrow()
        val assignmentId = UUID.fromString(created.id)

        val missingAssignment = service.updateLeadershipRole(
            memberId,
            UUID.randomUUID(),
            tuntasId,
            callerUserId = memberId,
            request = UpdateLeadershipRoleRequest(termStatus = "RESIGNED")
        )
        assertEquals("Leadership role assignment not found", missingAssignment.exceptionOrNull()?.message)

        val invalidStatus = service.updateLeadershipRole(
            memberId,
            assignmentId,
            tuntasId,
            callerUserId = memberId,
            request = UpdateLeadershipRoleRequest(termStatus = "UNKNOWN")
        )
        assertEquals("Invalid term status", invalidStatus.exceptionOrNull()?.message)

        val invalidOrgUnit = service.updateLeadershipRole(
            memberId,
            assignmentId,
            tuntasId,
            callerUserId = memberId,
            request = UpdateLeadershipRoleRequest(organizationalUnitId = "bad-uuid")
        )
        assertEquals("Invalid organizational unit ID", invalidOrgUnit.exceptionOrNull()?.message)

        val invalidStart = service.updateLeadershipRole(
            memberId,
            assignmentId,
            tuntasId,
            callerUserId = memberId,
            request = UpdateLeadershipRoleRequest(startsAt = "bad-date")
        )
        assertEquals("Invalid startsAt format, use ISO 8601", invalidStart.exceptionOrNull()?.message)

        val invalidExpiry = service.updateLeadershipRole(
            memberId,
            assignmentId,
            tuntasId,
            callerUserId = memberId,
            request = UpdateLeadershipRoleRequest(expiresAt = "bad-date")
        )
        assertEquals("Invalid expiresAt format, use ISO 8601", invalidExpiry.exceptionOrNull()?.message)

        val missingCaller = service.updateLeadershipRole(
            memberId,
            assignmentId,
            tuntasId,
            callerUserId = null,
            request = UpdateLeadershipRoleRequest(termStatus = "RESIGNED")
        )
        assertEquals("Caller user is required", missingCaller.exceptionOrNull()?.message)

        val activeAgain = service.updateLeadershipRole(
            memberId,
            assignmentId,
            tuntasId,
            callerUserId = memberId,
            request = UpdateLeadershipRoleRequest(termStatus = "ACTIVE")
        )
        assertTrue(activeAgain.isSuccess)

        val removedMissingCaller = service.removeLeadershipRole(
            memberId,
            assignmentId,
            tuntasId,
            callerUserId = null
        )
        assertEquals("Caller user is required", removedMissingCaller.exceptionOrNull()?.message)

        val removedMissingAssignment = service.removeLeadershipRole(
            memberId,
            UUID.randomUUID(),
            tuntasId,
            callerUserId = memberId
        )
        assertEquals("Leadership role assignment not found", removedMissingAssignment.exceptionOrNull()?.message)

        val activeRow = transaction {
            UserLeadershipRoles.selectAll()
                .where { UserLeadershipRoles.id eq assignmentId }
                .first()[UserLeadershipRoles.termStatus]
        }
        assertEquals("ACTIVE", activeRow)
    }

    @Test
    fun `principal unit leader resignation requires request approval and successor`() = testApplication {
        configureFullApp()
        val tuntininkasEmail = randomEmail("change-reviewer")
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = tuntininkasEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val unitIdText = createUnit(token, tuntasIdText, "Leadership Change Unit")
        val unitId = UUID.fromString(unitIdText)
        val (_, leaderIdText) = client.registerInvitedUser(
            token,
            tuntasIdText,
            "Draugininkas",
            randomEmail("current-leader"),
            unitIdText
        )
        val (_, successorIdText) = client.registerInvitedUser(
            token,
            tuntasIdText,
            "Skautas",
            randomEmail("successor"),
            unitIdText
        )
        val (_, outsideMemberIdText) = client.registerInvitedUser(
            token,
            tuntasIdText,
            "Skautas",
            randomEmail("outside-successor")
        )
        val reviewerUserId = transaction {
            Users.selectAll().where { Users.email eq tuntininkasEmail }.first()[Users.id]
        }
        val leaderId = UUID.fromString(leaderIdText)
        val successorId = UUID.fromString(successorIdText)
        val outsideMemberId = UUID.fromString(outsideMemberIdText)
        val assignmentId = transaction {
            UserLeadershipRoles.selectAll()
                .where {
                    (UserLeadershipRoles.userId eq leaderId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.organizationalUnitId eq unitId) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE")
                }
                .first()[UserLeadershipRoles.id]
        }

        val directStepDown = service.stepDownLeadershipRole(leaderId, assignmentId, tuntasId)
        assertEquals(
            "Vieneto vadovas negali atsistatydinti ar buti nuimtas be pakeitejo. Sukurkite atsistatydinimo prasyma ir patvirtindami paskirkite nauja vadova.",
            directStepDown.exceptionOrNull()?.message
        )

        val resignationRequest = leadershipChangeRequestService.createResignationRequest(
            callerUserId = leaderId,
            assignmentId = assignmentId,
            tuntasId = tuntasId,
            request = CreateLeadershipChangeRequest(reason = "Noriu perduoti pareigas")
        ).getOrThrow()
        assertEquals("PENDING", resignationRequest.status)

        val missingSuccessor = leadershipChangeRequestService.reviewRequest(
            requestId = UUID.fromString(resignationRequest.id),
            tuntasId = tuntasId,
            reviewerUserId = reviewerUserId,
            request = ReviewLeadershipChangeRequest(action = "APPROVE")
        )
        assertEquals("Successor user ID required", missingSuccessor.exceptionOrNull()?.message)

        val outsideSuccessor = leadershipChangeRequestService.reviewRequest(
            requestId = UUID.fromString(resignationRequest.id),
            tuntasId = tuntasId,
            reviewerUserId = reviewerUserId,
            request = ReviewLeadershipChangeRequest(action = "APPROVE", successorUserId = outsideMemberId.toString())
        )
        assertEquals(
            "Successor must be an active member of this unit before taking over",
            outsideSuccessor.exceptionOrNull()?.message
        )

        val approved = leadershipChangeRequestService.reviewRequest(
            requestId = UUID.fromString(resignationRequest.id),
            tuntasId = tuntasId,
            reviewerUserId = reviewerUserId,
            request = ReviewLeadershipChangeRequest(action = "APPROVE", successorUserId = successorId.toString())
        ).getOrThrow()
        assertEquals("APPROVED", approved.status)
        assertEquals(successorIdText, approved.successorUserId)

        val roleStates = transaction {
            val oldStatus = UserLeadershipRoles.selectAll()
                .where { UserLeadershipRoles.id eq assignmentId }
                .first()[UserLeadershipRoles.termStatus]
            val successorActive = UserLeadershipRoles.selectAll()
                .where {
                    (UserLeadershipRoles.userId eq successorId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.organizationalUnitId eq unitId) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE")
                }
                .count()
            val requestStatus = LeadershipChangeRequests.selectAll()
                .where { LeadershipChangeRequests.id eq UUID.fromString(resignationRequest.id) }
                .first()[LeadershipChangeRequests.status]
            Triple(oldStatus, successorActive, requestStatus)
        }
        assertEquals("RESIGNED", roleStates.first)
        assertEquals(1, roleStates.second)
        assertEquals("APPROVED", roleStates.third)
    }
}
