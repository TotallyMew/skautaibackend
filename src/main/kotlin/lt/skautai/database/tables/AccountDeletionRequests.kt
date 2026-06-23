package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object AccountDeletionRequests : Table("account_deletion_requests") {
    val id = uuid("id")
    val userId = uuid("user_id").references(Users.id)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val requestedVia = varchar("requested_via", 20)
    val expiresAt = timestamp("expires_at")
    val confirmedAt = timestamp("confirmed_at").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
