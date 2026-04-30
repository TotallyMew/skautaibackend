package lt.skautai

import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.createUnit
import lt.skautai.TestHelper.getRoleId
import lt.skautai.TestHelper.randomEmail
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.TestHelper.registerInvitedUser
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.UserRanks
import lt.skautai.services.ItemScopeHelper
import lt.skautai.services.LeadershipRoleRules
import lt.skautai.services.VadovasRankSupport
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceSupportTest {

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
    fun `item scope helper returns custodian and origin for matching tuntas only`() = testApplication {
        configureFullApp()
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas()
        val tuntasId = UUID.fromString(tuntasIdText)
        val unitId = UUID.fromString(createUnit(token, tuntasIdText, "Scope Unit"))
        val itemId = UUID.randomUUID()

        transaction {
            Items.insert {
                it[id] = itemId
                it[this.tuntasId] = tuntasId
                it[custodianId] = unitId
                it[origin] = "UNIT_ACQUIRED"
                it[name] = "Kirvis"
                it[description] = "Bandymo daiktas"
                it[type] = "COLLECTIVE"
                it[category] = "TOOLS"
                it[quantity] = 2
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }

        val info = ItemScopeHelper.getItemScopeInfo(itemId, tuntasId)
        assertNotNull(info)
        assertEquals(unitId, ItemScopeHelper.getItemCustodianId(itemId, tuntasId))
        assertEquals(unitId, info.custodianId)
        assertEquals("UNIT_ACQUIRED", info.origin)

        assertNull(ItemScopeHelper.getItemScopeInfo(itemId, UUID.randomUUID()))
        assertNull(ItemScopeHelper.getItemCustodianId(UUID.randomUUID(), tuntasId))
    }

    @Test
    fun `vadovas rank support inserts vadovas rank once for leadership user`() = testApplication {
        configureFullApp()
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas()
        val (_, userIdText) = client.registerInvitedUser(
            inviterToken = token,
            tuntasId = tuntasIdText,
            roleName = "Skautas",
            email = randomEmail("vadovas-once")
        )

        val userId = UUID.fromString(userIdText)
        val tuntasId = UUID.fromString(tuntasIdText)

        transaction {
            VadovasRankSupport.ensureVadovasRank(userId = userId, tuntasId = tuntasId, assignedByUserId = null)
            VadovasRankSupport.ensureVadovasRank(userId = userId, tuntasId = tuntasId, assignedByUserId = null)
        }

        val vadovasRoleId = UUID.fromString(getRoleId(tuntasIdText, "Vadovas"))
        val matchingRanks = transaction {
            UserRanks.selectAll()
                .where {
                    (UserRanks.userId eq userId) and
                        (UserRanks.tuntasId eq tuntasId) and
                        (UserRanks.roleId eq vadovasRoleId)
                }
                .count()
        }

        assertEquals(1, matchingRanks)
    }

    @Test
    fun `vadovas rank support backfills active leadership users only`() = testApplication {
        configureFullApp()
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasIdText, "Vadovai")
        val (_, activeLeaderIdText) = client.registerInvitedUser(
            inviterToken = token,
            tuntasId = tuntasIdText,
            roleName = "Skautas",
            email = randomEmail("active-leader")
        )
        val (_, formerLeaderIdText) = client.registerInvitedUser(
            inviterToken = token,
            tuntasId = tuntasIdText,
            roleName = "Skautas",
            email = randomEmail("former-leader")
        )

        val tuntasId = UUID.fromString(tuntasIdText)
        val draugininkasRoleId = UUID.fromString(getRoleId(tuntasIdText, "Draugininkas"))
        val activeLeaderId = UUID.fromString(activeLeaderIdText)
        val formerLeaderId = UUID.fromString(formerLeaderIdText)
        val formerUnitId = UUID.fromString(createUnit(token, tuntasIdText, "Buvusieji"))
        transaction {
            UserLeadershipRoles.insert {
                it[userId] = activeLeaderId
                it[roleId] = draugininkasRoleId
                it[this.tuntasId] = tuntasId
                it[organizationalUnitId] = UUID.fromString(unitId)
                it[assignedByUserId] = activeLeaderId
                it[termStatus] = "ACTIVE"
                it[leftAt] = null
            }
            UserLeadershipRoles.insert {
                it[userId] = formerLeaderId
                it[roleId] = draugininkasRoleId
                it[this.tuntasId] = tuntasId
                it[organizationalUnitId] = formerUnitId
                it[assignedByUserId] = activeLeaderId
                it[termStatus] = "RESIGNED"
                it[leftAt] = Clock.System.now()
            }
        }

        transaction {
            VadovasRankSupport.backfillExistingLeadershipUsers()
        }

        val vadovasRoleId = UUID.fromString(getRoleId(tuntasIdText, "Vadovas"))
        val activeLeaderRanks = transaction {
            UserRanks.selectAll()
                .where {
                    (UserRanks.userId eq UUID.fromString(activeLeaderIdText)) and
                        (UserRanks.tuntasId eq tuntasId) and
                        (UserRanks.roleId eq vadovasRoleId)
                }
                .count()
        }
        val formerLeaderRanks = transaction {
            UserRanks.selectAll()
                .where {
                    (UserRanks.userId eq formerLeaderId) and
                        (UserRanks.tuntasId eq tuntasId) and
                        (UserRanks.roleId eq vadovasRoleId)
                }
                .count()
        }

        assertEquals(1, activeLeaderRanks)
        assertEquals(0, formerLeaderRanks)
    }

    @Test
    fun `leadership role rules detect principal leaders and occupied slots`() = testApplication {
        configureFullApp()
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas()
        val tuntasId = UUID.fromString(tuntasIdText)
        val unitId = UUID.fromString(createUnit(token, tuntasIdText, "Principal Unit"))

        assertTrue(LeadershipRoleRules.isPrincipalUnitLeader("Draugininkas"))
        assertTrue(LeadershipRoleRules.requiresOrganizationalUnit("Draugininkas"))
        assertTrue(LeadershipRoleRules.isTuntininkas("Tuntininkas"))
        transaction {
            assertNull(LeadershipRoleRules.validatePrincipalUnitLeaderSlot(UUID.randomUUID(), tuntasId, null))
        }

        val draugininkasRoleId = UUID.fromString(getRoleId(tuntasIdText, "Draugininkas"))
        val inventorininkasRoleId = UUID.fromString(getRoleId(tuntasIdText, "Inventorininkas"))

        val noConflict = transaction {
            LeadershipRoleRules.validatePrincipalUnitLeaderSlot(inventorininkasRoleId, tuntasId, unitId)
        }
        assertNull(noConflict)

        client.registerInvitedUser(
            inviterToken = token,
            tuntasId = tuntasIdText,
            roleName = "Draugininkas",
            email = randomEmail("principal-leader"),
            organizationalUnitId = unitId.toString()
        )

        val conflict = transaction {
            LeadershipRoleRules.validatePrincipalUnitLeaderSlot(draugininkasRoleId, tuntasId, unitId)
        }
        assertEquals("This unit already has an active draugininkas or pirmininkas", conflict)

        val assignmentId: UUID? = transaction {
            UserLeadershipRoles
                .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
                .selectAll()
                .where {
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.organizationalUnitId eq unitId) and
                        (Roles.name eq "Draugininkas")
                }
                .firstOrNull()
                ?.get(UserLeadershipRoles.id)
        }
        assertNotNull(assignmentId)
        transaction {
            assertNull(
                LeadershipRoleRules.validatePrincipalUnitLeaderSlot(
                    roleId = draugininkasRoleId,
                    tuntasId = tuntasId,
                    organizationalUnitId = unitId,
                    excludeAssignmentId = assignmentId
                )
            )
        }

        val missingRoleResult = transaction {
            LeadershipRoleRules.validatePrincipalUnitLeaderSlot(UUID.randomUUID(), tuntasId, unitId)
        }
        assertEquals("Role not found in this tuntas", missingRoleResult)
    }
}
