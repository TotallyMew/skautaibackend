package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object AuthLoginThrottles : Table("auth_login_throttles") {
    val key = varchar("key", 320)
    val failedCount = integer("failed_count")
    val windowStartedAt = timestamp("window_started_at")
    val blockedUntil = timestamp("blocked_until").nullable()
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(key)
}
