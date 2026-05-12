package lt.skautai.services

import lt.skautai.database.tables.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object PermissionSeeder {

    // All global permissions
    private val globalPermissions = listOf(
        "items.view",
        "items.create",
        "items.update",
        "items.delete",
        "items.transfer",
        "items.request.bendras",
        "items.request.approve.unit",
        "items.request.forward.bendras",
        "items.request.approve.bendras",
        "members.view",
        "members.manage",
        "roles.assign",
        "invitations.create",
        "locations.manage",
        "organizational_units.view",
        "organizational_units.manage",
        "reservations.view",
        "reservations.create",
        "reservations.approve",
        "requisitions.create",
        "requisitions.approve",
        "members.remove",
        "unit.members.manage",
        "items.create.submit",
        "items.review"
    )

    // All event permissions
    private val eventPermissions = listOf(
        "events.view",
        "events.create",
        "events.manage",
        "events.inventory.distribute",
        "events.inventory.return",
        "event_purchases.invoice.download"
    )

    // Role name -> list of Pair(permissionName, scope)
    // scope ALL     = applies tuntas-wide
    // scope OWN_UNIT = applies only to the user's assigned organizational unit
    private val rolePermissionMap = mapOf(

        // ── Tuntas-level leadership ──────────────────────────────────────────

        "Tuntininkas" to listOf(
            "items.view" to "ALL",
            "items.create" to "ALL",
            "items.update" to "ALL",
            "items.delete" to "ALL",
            "items.transfer" to "ALL",
            "items.request.bendras" to "ALL",
            "items.request.approve.unit" to "ALL",
            "items.request.approve.bendras" to "ALL",
            "items.review" to "ALL",
            "members.view" to "ALL",
            "members.manage" to "ALL",
            "roles.assign" to "ALL",
            "invitations.create" to "ALL",
            "locations.manage" to "ALL",
            "organizational_units.view" to "ALL",
            "organizational_units.manage" to "ALL",
            "reservations.view" to "ALL",
            "reservations.create" to "ALL",
            "reservations.approve" to "ALL",
            "requisitions.create" to "ALL",
            "requisitions.approve" to "ALL",
            "events.view" to "ALL",
            "events.create" to "ALL",
            "events.manage" to "ALL",
            "events.inventory.distribute" to "ALL",
            "events.inventory.return" to "ALL",
            "members.remove" to "ALL",
            "unit.members.manage" to "ALL"
        ),

        "Tuntininko pavaduotojas" to listOf(
            "items.view" to "ALL",
            "items.create" to "ALL",
            "items.update" to "ALL",
            "items.delete" to "ALL",
            "items.transfer" to "ALL",
            "items.request.bendras" to "ALL",
            "items.request.approve.unit" to "ALL",
            "items.request.approve.bendras" to "ALL",
            "items.review" to "ALL",
            "members.view" to "ALL",
            "members.manage" to "ALL",
            "roles.assign" to "ALL",
            "invitations.create" to "ALL",
            "locations.manage" to "ALL",
            "organizational_units.view" to "ALL",
            "organizational_units.manage" to "ALL",
            "reservations.view" to "ALL",
            "reservations.create" to "ALL",
            "reservations.approve" to "ALL",
            "requisitions.create" to "ALL",
            "requisitions.approve" to "ALL",
            "events.view" to "ALL",
            "events.create" to "ALL",
            "events.manage" to "ALL",
            "events.inventory.distribute" to "ALL",
            "events.inventory.return" to "ALL",
            "members.remove" to "ALL",
            "unit.members.manage" to "ALL"
        ),

        // ── Tuntas-wide special role ─────────────────────────────────────────

        "Inventorininkas" to listOf(
            "items.view" to "ALL",
            "items.create" to "ALL",
            "items.update" to "ALL",
            "items.delete" to "ALL",
            "items.transfer" to "ALL",
            "items.request.bendras" to "ALL",
            "items.request.approve.bendras" to "ALL",
            "items.review" to "ALL",
            "locations.manage" to "ALL",
            "reservations.view" to "ALL",
            "reservations.create" to "ALL",
            "reservations.approve" to "ALL",
            "requisitions.create" to "ALL",
            "requisitions.approve" to "ALL",
            "events.view" to "ALL",
            "events.inventory.distribute" to "ALL",
            "events.inventory.return" to "ALL"
        ),

        "Finansininkas" to listOf(
            "events.view" to "ALL",
            "event_purchases.invoice.download" to "ALL"
        ),

        // ── Unit-level leadership (Draugovė / Gildija / Vyr. vienetas) ───────
        // All unit-level leaders share the same permission set, scoped to OWN_UNIT.
        // The roles differ by name only (to enforce one-per-unit and unit-type awareness).

        "Draugininkas" to listOf(
            "items.view" to "OWN_UNIT",
            "items.create" to "OWN_UNIT",
            "items.update" to "OWN_UNIT",
            "items.review" to "OWN_UNIT",
            "items.request.approve.unit" to "OWN_UNIT",
            "items.request.forward.bendras" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "members.view" to "OWN_UNIT",
            "invitations.create" to "OWN_UNIT",
            "locations.manage" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_UNIT",
            "requisitions.create" to "OWN_UNIT",
            "events.view" to "ALL",
            "events.create" to "OWN_UNIT",
            "events.manage" to "OWN_UNIT",
            "events.inventory.distribute" to "OWN_UNIT",
            "events.inventory.return" to "OWN_UNIT",
            "unit.members.manage" to "OWN_UNIT"
        ),

        "Draugininko pavaduotojas" to listOf(
            "items.view" to "OWN_UNIT",
            "items.create" to "OWN_UNIT",
            "items.update" to "OWN_UNIT",
            "items.review" to "OWN_UNIT",
            "items.request.approve.unit" to "OWN_UNIT",
            "items.request.forward.bendras" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "members.view" to "OWN_UNIT",
            "invitations.create" to "OWN_UNIT",
            "locations.manage" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_UNIT",
            "requisitions.create" to "OWN_UNIT",
            "events.view" to "ALL",
            "events.create" to "OWN_UNIT",
            "events.manage" to "OWN_UNIT",
            "events.inventory.distribute" to "OWN_UNIT",
            "events.inventory.return" to "OWN_UNIT",
            "unit.members.manage" to "OWN_UNIT"
        ),

        "Gildijos pirmininkas" to listOf(
            "items.view" to "OWN_UNIT",
            "items.create" to "OWN_UNIT",
            "items.update" to "OWN_UNIT",
            "items.review" to "OWN_UNIT",
            "items.request.approve.unit" to "OWN_UNIT",
            "items.request.forward.bendras" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "members.view" to "OWN_UNIT",
            "invitations.create" to "OWN_UNIT",
            "locations.manage" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_UNIT",
            "requisitions.create" to "OWN_UNIT",
            "events.view" to "ALL",
            "events.create" to "OWN_UNIT",
            "events.manage" to "OWN_UNIT",
            "events.inventory.distribute" to "OWN_UNIT",
            "events.inventory.return" to "OWN_UNIT",
            "unit.members.manage" to "OWN_UNIT"
        ),

        "Gildijos pirmininko pavaduotojas" to listOf(
            "items.view" to "OWN_UNIT",
            "items.create" to "OWN_UNIT",
            "items.update" to "OWN_UNIT",
            "items.review" to "OWN_UNIT",
            "items.request.approve.unit" to "OWN_UNIT",
            "items.request.forward.bendras" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "members.view" to "OWN_UNIT",
            "invitations.create" to "OWN_UNIT",
            "locations.manage" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_UNIT",
            "requisitions.create" to "OWN_UNIT",
            "events.view" to "ALL",
            "events.create" to "OWN_UNIT",
            "events.manage" to "OWN_UNIT",
            "events.inventory.distribute" to "OWN_UNIT",
            "events.inventory.return" to "OWN_UNIT",
            "unit.members.manage" to "OWN_UNIT"
        ),

        "Vyr. skautu draugoves draugininkas" to listOf(
            "items.view" to "OWN_UNIT",
            "items.create" to "OWN_UNIT",
            "items.update" to "OWN_UNIT",
            "items.review" to "OWN_UNIT",
            "items.request.approve.unit" to "OWN_UNIT",
            "items.request.forward.bendras" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "members.view" to "OWN_UNIT",
            "invitations.create" to "OWN_UNIT",
            "locations.manage" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_UNIT",
            "requisitions.create" to "OWN_UNIT",
            "events.view" to "ALL",
            "events.create" to "OWN_UNIT",
            "events.manage" to "OWN_UNIT",
            "events.inventory.distribute" to "OWN_UNIT",
            "events.inventory.return" to "OWN_UNIT",
            "unit.members.manage" to "OWN_UNIT"
        ),

        "Vyr. skautu draugoves draugininko pavaduotojas" to listOf(
            "items.view" to "OWN_UNIT",
            "items.create" to "OWN_UNIT",
            "items.update" to "OWN_UNIT",
            "items.review" to "OWN_UNIT",
            "items.request.approve.unit" to "OWN_UNIT",
            "items.request.forward.bendras" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "members.view" to "OWN_UNIT",
            "invitations.create" to "OWN_UNIT",
            "locations.manage" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_UNIT",
            "requisitions.create" to "OWN_UNIT",
            "events.view" to "ALL",
            "events.create" to "OWN_UNIT",
            "events.manage" to "OWN_UNIT",
            "events.inventory.distribute" to "OWN_UNIT",
            "events.inventory.return" to "OWN_UNIT",
            "unit.members.manage" to "OWN_UNIT"
        ),

        "Vyr. skautu burelio pirmininkas" to listOf(
            "items.view" to "OWN_UNIT",
            "items.create" to "OWN_UNIT",
            "items.update" to "OWN_UNIT",
            "items.review" to "OWN_UNIT",
            "items.request.approve.unit" to "OWN_UNIT",
            "items.request.forward.bendras" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "members.view" to "OWN_UNIT",
            "invitations.create" to "OWN_UNIT",
            "locations.manage" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_UNIT",
            "requisitions.create" to "OWN_UNIT",
            "events.view" to "ALL",
            "events.create" to "OWN_UNIT",
            "events.manage" to "OWN_UNIT",
            "events.inventory.distribute" to "OWN_UNIT",
            "events.inventory.return" to "OWN_UNIT",
            "unit.members.manage" to "OWN_UNIT"
        ),

        "Vyr. skautu burelio pirmininko pavaduotojas" to listOf(
            "items.view" to "OWN_UNIT",
            "items.create" to "OWN_UNIT",
            "items.update" to "OWN_UNIT",
            "items.review" to "OWN_UNIT",
            "items.request.approve.unit" to "OWN_UNIT",
            "items.request.forward.bendras" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "members.view" to "OWN_UNIT",
            "invitations.create" to "OWN_UNIT",
            "locations.manage" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_UNIT",
            "requisitions.create" to "OWN_UNIT",
            "events.view" to "ALL",
            "events.create" to "OWN_UNIT",
            "events.manage" to "OWN_UNIT",
            "events.inventory.distribute" to "OWN_UNIT",
            "events.inventory.return" to "OWN_UNIT",
            "unit.members.manage" to "OWN_UNIT"
        ),

        "Vyr. skauciu draugoves draugininkas" to listOf(
            "items.view" to "OWN_UNIT",
            "items.create" to "OWN_UNIT",
            "items.update" to "OWN_UNIT",
            "items.review" to "OWN_UNIT",
            "items.request.approve.unit" to "OWN_UNIT",
            "items.request.forward.bendras" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "members.view" to "OWN_UNIT",
            "invitations.create" to "OWN_UNIT",
            "locations.manage" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_UNIT",
            "requisitions.create" to "OWN_UNIT",
            "events.view" to "ALL",
            "events.create" to "OWN_UNIT",
            "events.manage" to "OWN_UNIT",
            "events.inventory.distribute" to "OWN_UNIT",
            "events.inventory.return" to "OWN_UNIT",
            "unit.members.manage" to "OWN_UNIT"
        ),

        "Vyr. skauciu draugoves draugininko pavaduotojas" to listOf(
            "items.view" to "OWN_UNIT",
            "items.create" to "OWN_UNIT",
            "items.update" to "OWN_UNIT",
            "items.review" to "OWN_UNIT",
            "items.request.approve.unit" to "OWN_UNIT",
            "items.request.forward.bendras" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "members.view" to "OWN_UNIT",
            "invitations.create" to "OWN_UNIT",
            "locations.manage" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_UNIT",
            "requisitions.create" to "OWN_UNIT",
            "events.view" to "ALL",
            "events.create" to "OWN_UNIT",
            "events.manage" to "OWN_UNIT",
            "events.inventory.distribute" to "OWN_UNIT",
            "events.inventory.return" to "OWN_UNIT",
            "unit.members.manage" to "OWN_UNIT"
        ),

        "Vyr. skauciu burelio pirmininkas" to listOf(
            "items.view" to "OWN_UNIT",
            "items.create" to "OWN_UNIT",
            "items.update" to "OWN_UNIT",
            "items.review" to "OWN_UNIT",
            "items.request.approve.unit" to "OWN_UNIT",
            "items.request.forward.bendras" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "members.view" to "OWN_UNIT",
            "invitations.create" to "OWN_UNIT",
            "locations.manage" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_UNIT",
            "requisitions.create" to "OWN_UNIT",
            "events.view" to "ALL",
            "events.create" to "OWN_UNIT",
            "events.manage" to "OWN_UNIT",
            "events.inventory.distribute" to "OWN_UNIT",
            "events.inventory.return" to "OWN_UNIT",
            "unit.members.manage" to "OWN_UNIT"
        ),

        "Vyr. skauciu burelio pirmininko pavaduotojas" to listOf(
            "items.view" to "OWN_UNIT",
            "items.create" to "OWN_UNIT",
            "items.update" to "OWN_UNIT",
            "items.review" to "OWN_UNIT",
            "items.request.approve.unit" to "OWN_UNIT",
            "items.request.forward.bendras" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "members.view" to "OWN_UNIT",
            "invitations.create" to "OWN_UNIT",
            "locations.manage" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_UNIT",
            "requisitions.create" to "OWN_UNIT",
            "events.view" to "ALL",
            "events.create" to "OWN_UNIT",
            "events.manage" to "OWN_UNIT",
            "events.inventory.distribute" to "OWN_UNIT",
            "events.inventory.return" to "OWN_UNIT",
            "unit.members.manage" to "OWN_UNIT"
        ),

        // ── Ranks ────────────────────────────────────────────────────────────
        // Skautai: view and create reservations only
        // Patyres skautas: same as above plus bendras request
        // Vyr. skautas kandidatas / Vyr. skautas: same as Patyres skautas
        // Vadovas: broader read access across the tuntas, without unit/member edit permissions.

        "Skautas" to listOf(
            "items.view" to "OWN_UNIT",
            "members.view" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "requisitions.create" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL"
        ),

        "Patyres skautas" to listOf(
            "items.view" to "OWN_UNIT",
            "members.view" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "requisitions.create" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "events.view" to "ALL",
            "events.create" to "ALL"
        ),

        "Vyr. skautas kandidatas" to listOf(
            "items.view" to "OWN_UNIT",
            "members.view" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "requisitions.create" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL"
        ),

        "Vyr. skautas" to listOf(
            "items.view" to "OWN_UNIT",
            "members.view" to "OWN_UNIT",
            "organizational_units.view" to "OWN_UNIT",
            "items.request.bendras" to "ALL",
            "requisitions.create" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL"
        ),

        "Vadovas" to listOf(
            "items.view" to "OWN_UNIT",
            "items.create.submit" to "OWN_UNIT",
            "members.view" to "ALL",
            "organizational_units.view" to "ALL",
            "items.request.bendras" to "ALL",
            "requisitions.create" to "OWN_UNIT",
            "invitations.create" to "OWN_UNIT",
            "reservations.view" to "OWN_UNIT",
            "reservations.create" to "ALL",
            "events.view" to "ALL",
            "events.create" to "ALL"
        )
    )

    fun seedPermissions() {
        transaction {
            val allPermissions = globalPermissions.map { it to "GLOBAL" } + eventPermissions.map { it to "EVENT" }
            for ((permName, ctx) in allPermissions) {
                if (Permissions.selectAll().where { Permissions.name eq permName }.firstOrNull() == null) {
                    Permissions.insert {
                        it[name] = permName
                        it[context] = ctx
                    }
                }
            }
        }
    }

    fun seedRolePermissions(tuntasId: UUID) {
        transaction {
            ensureFinansininkasRole(tuntasId)
            val permissionIds = Permissions.selectAll()
                .associate { it[Permissions.name] to it[Permissions.id] }

            val roleIds = Roles.selectAll()
                .where { Roles.tuntasId eq tuntasId }
                .associate { it[Roles.name] to it[Roles.id] }

            for ((roleName, permissions) in rolePermissionMap) {
                val roleId = roleIds[roleName] ?: continue
                val desiredPermissionIds = permissions.mapNotNull { (permName, _) -> permissionIds[permName] }

                RolePermissions.deleteWhere {
                    (RolePermissions.roleId eq roleId) and
                        (RolePermissions.permissionId notInList desiredPermissionIds)
                }

                for ((permName, scope) in permissions) {
                    val permId = permissionIds[permName] ?: continue

                    val exists = RolePermissions.selectAll()
                        .where {
                            (RolePermissions.roleId eq roleId) and
                                    (RolePermissions.permissionId eq permId)
                        }
                        .firstOrNull()

                    if (exists == null) {
                        RolePermissions.insert {
                            it[RolePermissions.roleId] = roleId
                            it[RolePermissions.permissionId] = permId
                            it[RolePermissions.scope] = scope
                        }
                    } else if (exists[RolePermissions.scope] != scope) {
                        RolePermissions.update({ RolePermissions.id eq exists[RolePermissions.id] }) {
                            it[RolePermissions.scope] = scope
                        }
                    }
                }
            }
        }
    }

    private fun ensureFinansininkasRole(tuntasId: UUID) {
        val exists = Roles.selectAll()
            .where { (Roles.tuntasId eq tuntasId) and (Roles.name eq "Finansininkas") }
            .firstOrNull() != null
        if (!exists) {
            Roles.insert {
                it[Roles.tuntasId] = tuntasId
                it[Roles.name] = "Finansininkas"
                it[Roles.isSystemRole] = true
                it[Roles.roleType] = "LEADERSHIP"
            }
        }
    }
}
