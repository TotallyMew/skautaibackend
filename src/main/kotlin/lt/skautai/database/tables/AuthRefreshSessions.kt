package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object AuthRefreshSessions : Table("auth_refresh_sessions") {
    val id = uuid("id")
    val subjectId = uuid("subject_id")
    val subjectType = varchar("subject_type", 20)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val expiresAt = timestamp("expires_at")
    val revokedAt = timestamp("revoked_at").nullable()
    val replacedBySessionId = uuid("replaced_by_session_id").nullable()
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
