package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Notifications : Table("notifications") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(Users.id)
    val tuntasId = uuid("tuntas_id").references(Tuntai.id).nullable()
    val title = varchar("title", 120)
    val body = varchar("body", 1000)
    val resource = varchar("resource", 80).nullable()
    val entityId = uuid("entity_id").nullable()
    val data = text("data").nullable()
    val readAt = timestamp("read_at").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
