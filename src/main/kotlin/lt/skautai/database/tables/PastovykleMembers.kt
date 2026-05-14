package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object PastovykleMembers : Table("pastovykle_members") {
    val id = uuid("id").autoGenerate()
    val pastovykleId = uuid("pastovykle_id").references(Pastovykles.id)
    val userId = uuid("user_id").references(Users.id)
    val status = varchar("status", 20).default("ACTIVE")
    val addedAt = timestamp("added_at")
    val addedByUserId = uuid("added_by_user_id").references(Users.id)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(pastovykleId, userId)
    }
}
