package lt.skautai.services

import lt.skautai.database.tables.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object PermissionSeeder {

    // All global permissions
    private val globalPermissions = listOf(
        "items.view",
        "items.create",
        "items.update",
        "items.delete",
        "items.request.draugove",
        "items.request.bendras",
        "items.request.approve.draugove",
        "items.request.forward.bendras",
        "items.request.approve.bendras",
        "members.view",
        "members.manage",
        "roles.assign",
        "invitations.create",
        "locations.manage",
        "organizational_units.manage",
        "reservations.view",
        "reservations.create",
        "reservations.approve",
        "requisitions.create",
        "requisitions.approve",
        "members.remove",
        "draugove.members.manage"
    )

    // All event permissions
    private val eventPermissions = listOf(
        "events.view",
        "events.create",
        "events.manage",
        "events.inventory.distribute",
        "events.inventory.return"
    )

    // Role name -> list of Pair(permissionName, scope)
    private val rolePermissionMap = mapOf(
        "Tuntininkas" to listOf(
            "items.view" to "ALL",
            "items.create" to "ALL",
            "items.update" to "ALL",
            "items.delete" to "ALL",
            "items.request.approve.draugove" to "ALL",
            "items.request.approve.bendras" to "ALL",
            "members.view" to "ALL",
            "members.manage" to "ALL",
            "roles.assign" to "ALL",
            "invitations.create" to "ALL",
            "locations.manage" to "ALL",
            "organizational_units.manage" to "ALL",
            "reservations.view" to "ALL",
            "reservations.create" to "ALL",
            "reservations.approve" to "ALL",
            "requisitions.approve" to "ALL",
            "events.view" to "ALL",
            "events.create" to "ALL",
            "events.manage" to "ALL",
            "events.inventory.distribute" to "ALL",
            "events.inventory.return" to "ALL",
            "members.remove" to "ALL",
            "draugove.members.manage" to "ALL",
            "items.request.bendras" to "ALL",
        ),
        "Tuntininko pavaduotojas" to listOf(
            "items.view" to "ALL",
            "items.create" to "ALL",
            "items.update" to "ALL",
            "items.delete" to "ALL",
            "items.request.approve.draugove" to "ALL",
            "items.request.approve.bendras" to "ALL",
            "members.view" to "ALL",
            "members.manage" to "ALL",
            "roles.assign" to "ALL",
            "invitations.create" to "ALL",
            "locations.manage" to "ALL",
            "organizational_units.manage" to "ALL",
            "reservations.view" to "ALL",
            "reservations.create" to "ALL",
            "reservations.approve" to "ALL",
            "requisitions.approve" to "ALL",
            "events.view" to "ALL",
            "events.create" to "ALL",
            "events.manage" to "ALL",
            "events.inventory.distribute" to "ALL",
            "events.inventory.return" to "ALL",
            "members.remove" to "ALL",
            "draugove.members.manage" to "ALL",
            "items.request.bendras" to "ALL",
        ),
        "Draugininkas" to listOf(
            "items.view" to "ALL",
            "items.create" to "OWN_DRAUGOVE",
            "items.update" to "OWN_DRAUGOVE",
            "items.request.approve.draugove" to "OWN_DRAUGOVE",
            "items.request.forward.bendras" to "OWN_DRAUGOVE",
            "members.view" to "ALL",
            "invitations.create" to "OWN_DRAUGOVE",
            "reservations.view" to "ALL",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_DRAUGOVE",
            "requisitions.create" to "OWN_DRAUGOVE",
            "events.view" to "ALL",
            "events.create" to "OWN_DRAUGOVE",
            "events.manage" to "OWN_DRAUGOVE",
            "events.inventory.distribute" to "OWN_DRAUGOVE",
            "events.inventory.return" to "OWN_DRAUGOVE",
            "draugove.members.manage" to "OWN_DRAUGOVE",
            "items.request.bendras" to "ALL",
        ),
        "Draugininko pavaduotojas" to listOf(
            "items.view" to "ALL",
            "items.create" to "OWN_DRAUGOVE",
            "items.update" to "OWN_DRAUGOVE",
            "items.request.approve.draugove" to "OWN_DRAUGOVE",
            "items.request.forward.bendras" to "OWN_DRAUGOVE",
            "members.view" to "ALL",
            "invitations.create" to "OWN_DRAUGOVE",
            "reservations.view" to "ALL",
            "reservations.create" to "ALL",
            "reservations.approve" to "OWN_DRAUGOVE",
            "requisitions.create" to "OWN_DRAUGOVE",
            "events.view" to "ALL",
            "events.create" to "OWN_DRAUGOVE",
            "events.manage" to "OWN_DRAUGOVE",
            "events.inventory.distribute" to "OWN_DRAUGOVE",
            "events.inventory.return" to "OWN_DRAUGOVE",
            "draugove.members.manage" to "OWN_DRAUGOVE",
            "items.request.bendras" to "ALL",
        ),
        "Inventorininkas" to listOf(
            "items.view" to "ALL",
            "items.create" to "ALL",
            "items.update" to "ALL",
            "items.delete" to "ALL",
            "items.request.approve.draugove" to "ALL",
            "items.request.approve.bendras" to "ALL",
            "members.view" to "ALL",
            "locations.manage" to "ALL",
            "reservations.view" to "ALL",
            "reservations.create" to "ALL",
            "reservations.approve" to "ALL",
            "requisitions.approve" to "ALL",
            "events.view" to "ALL",
            "events.inventory.distribute" to "ALL",
            "events.inventory.return" to "ALL"
        ),
        "Skautas" to listOf(
            "items.view" to "ALL",
            "items.request.draugove" to "ALL",
            "items.request.bendras" to "ALL",
            "reservations.view" to "ALL",
            "reservations.create" to "ALL",
            "events.view" to "ALL"
        ),
        "Patyres skautas" to listOf(
            "items.view" to "ALL",
            "items.request.draugove" to "ALL",
            "items.request.bendras" to "ALL",
            "reservations.view" to "ALL",
            "reservations.create" to "ALL",
            "events.view" to "ALL"
        ),
        "Suauges skautybeje" to listOf(
            "items.view" to "ALL",
            "items.request.draugove" to "ALL",
            "items.request.bendras" to "ALL",
            "reservations.view" to "ALL",
            "reservations.create" to "ALL",
            "events.view" to "ALL",
            "events.create" to "ALL"
        )
    )

    fun seedPermissions() {
        transaction {
            val existingCount = Permissions.selectAll().count()
            if (existingCount > 0L) return@transaction

            for (permName in globalPermissions) {
                Permissions.insert {
                    it[name] = permName
                    it[context] = "GLOBAL"
                }
            }

            for (permName in eventPermissions) {
                Permissions.insert {
                    it[name] = permName
                    it[context] = "EVENT"
                }
            }
        }
    }

    fun seedRolePermissions(tuntasId: UUID) {
        transaction {
            // Load all permission IDs into a map for fast lookup
            val permissionIds = Permissions.selectAll()
                .associate { it[Permissions.name] to it[Permissions.id] }

            // Load all role IDs for this tuntas
            val roleIds = Roles.selectAll()
                .where { Roles.tuntasId eq tuntasId }
                .associate { it[Roles.name] to it[Roles.id] }

            for ((roleName, permissions) in rolePermissionMap) {
                val roleId = roleIds[roleName] ?: continue

                for ((permName, scope) in permissions) {
                    val permId = permissionIds[permName] ?: continue

                    // Avoid duplicate inserts
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
                    }
                }
            }
        }
    }
}