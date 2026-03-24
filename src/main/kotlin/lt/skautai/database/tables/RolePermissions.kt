package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table

object RolePermissions : Table("role_permissions") {
    val id = uuid("id").autoGenerate()
    val roleId = uuid("role_id").references(Roles.id)
    val permissionId = uuid("permission_id").references(Permissions.id)
    val scope = varchar("scope", 20).default("ALL")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(roleId, permissionId)
    }
}