package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Roles : Table("roles") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id).nullable()
    val name = varchar("name", 100)
    val isSystemRole = bool("is_system_role").default(false)
    val roleType = varchar("role_type", 20).default("LEADERSHIP")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}