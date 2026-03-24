package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object UserRanks : Table("user_ranks") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(Users.id)
    val roleId = uuid("role_id").references(Roles.id)
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val assignedByUserId = uuid("assigned_by_user_id")
        .references(Users.id).nullable()
    val assignedAt = timestamp("assigned_at")

    override val primaryKey = PrimaryKey(id)
}