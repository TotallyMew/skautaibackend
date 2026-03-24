package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ItemAdditionRequests : Table("item_addition_requests") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val requestedByUserId = uuid("requested_by_user_id").references(Users.id)
    val reviewedByUserId = uuid("reviewed_by_user_id").references(Users.id).nullable()
    val targetOwnerType = varchar("target_owner_type", 20)
    val targetOwnerId = uuid("target_owner_id")
    val itemName = varchar("item_name", 200)
    val description = text("description").nullable()
    val quantity = integer("quantity").default(1)
    val category = varchar("category", 20).nullable()
    val status = varchar("status", 20).default("PENDING")
    val rejectionReason = text("rejection_reason").nullable()
    val createdAt = timestamp("created_at")
    val reviewedAt = timestamp("reviewed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}