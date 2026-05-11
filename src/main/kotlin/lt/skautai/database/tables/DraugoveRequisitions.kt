package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object DraugoveRequisitions : Table("draugove_requisitions") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val organizationalUnitId = uuid("organizational_unit_id")
        .references(OrganizationalUnits.id)
        .nullable()
    val eventId = uuid("event_id").references(Events.id).nullable()
    val createdByUserId = uuid("created_by_user_id").references(Users.id)
    val reviewedByUserId = uuid("reviewed_by_user_id").references(Users.id).nullable()
    val status = varchar("status", 30).default("DRAFT")
    val unitReviewStatus = varchar("unit_review_status", 20).default("PENDING")
    val unitReviewedByUserId = uuid("unit_reviewed_by_user_id").references(Users.id).nullable()
    val unitReviewedAt = timestamp("unit_reviewed_at").nullable()
    val topLevelReviewStatus = varchar("top_level_review_status", 20).default("NOT_REQUIRED")
    val topLevelReviewedByUserId = uuid("top_level_reviewed_by_user_id").references(Users.id).nullable()
    val topLevelReviewedAt = timestamp("top_level_reviewed_at").nullable()
    val purchasedAt = timestamp("purchased_at").nullable()
    val purchasedByUserId = uuid("purchased_by_user_id").references(Users.id).nullable()
    val addedToInventoryAt = timestamp("added_to_inventory_at").nullable()
    val addedToInventoryByUserId = uuid("added_to_inventory_by_user_id").references(Users.id).nullable()
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
