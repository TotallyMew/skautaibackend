package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ItemTransfers : Table("item_transfers") {
    val id = uuid("id").autoGenerate()
    val itemId = uuid("item_id").references(Items.id)
    val fromOwnerType = varchar("from_owner_type", 20)
    val fromOwnerId = uuid("from_owner_id")
    val toOwnerType = varchar("to_owner_type", 20)
    val toOwnerId = uuid("to_owner_id")
    val transferReason = varchar("transfer_reason", 30)
    val initiatedByUserId = uuid("initiated_by_user_id").references(Users.id).nullable()
    val approvedByUserId = uuid("approved_by_user_id").references(Users.id).nullable()
    val status = varchar("status", 20).default("PENDING")
    val createdAt = timestamp("created_at")
    val completedAt = timestamp("completed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}