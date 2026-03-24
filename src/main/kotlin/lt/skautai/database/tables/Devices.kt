package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Devices : Table("devices") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(Users.id)
    val deviceName = varchar("device_name", 100).nullable()
    val deviceToken = text("device_token").uniqueIndex()
    val lastSyncAt = timestamp("last_sync_at").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}