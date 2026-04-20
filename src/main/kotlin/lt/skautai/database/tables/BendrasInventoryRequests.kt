package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object BendrasInventoryRequests : Table("bendras_inventory_requests") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val requestedByUserId = uuid("requested_by_user_id").references(Users.id)
    val itemId = uuid("item_id").references(Items.id).nullable()
    val itemDescription = text("item_description").nullable()
    val quantity = integer("quantity").default(1)
    val eventId = uuid("event_id").references(Events.id).nullable()
    val requestingUnitId = uuid("requesting_unit_id").references(OrganizationalUnits.id).nullable()
    val needsDraugininkasApproval = bool("needs_draugininkas_approval").default(false)
    val draugininkasStatus = varchar("draugininkas_status", 20).nullable()
    val draugininkasReviewedByUserId = uuid("draugininkas_reviewed_by_user_id")
        .references(Users.id).nullable()
    val draugininkasRejectionReason = text("draugininkas_rejection_reason").nullable()
    val topLevelStatus = varchar("top_level_status", 20).default("PENDING")
    val topLevelReviewedByUserId = uuid("top_level_reviewed_by_user_id")
        .references(Users.id).nullable()
    val topLevelRejectionReason = text("top_level_rejection_reason").nullable()
    val startDate = date("start_date").nullable()
    val endDate = date("end_date").nullable()
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}