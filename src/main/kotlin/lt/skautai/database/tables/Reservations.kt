package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Reservations : Table("reservations") {
    val id = uuid("id").autoGenerate()
    val groupId = uuid("group_id")
    val title = varchar("title", 200)
    val itemId = uuid("item_id").references(Items.id)
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val reservedByUserId = uuid("reserved_by_user_id").references(Users.id)
    val approvedByUserId = uuid("approved_by_user_id").references(Users.id).nullable()
    val requestingUnitId = uuid("requesting_unit_id").references(OrganizationalUnits.id).nullable()
    val eventId = uuid("event_id").references(Events.id).nullable()
    val quantity = integer("quantity").default(1)
    val startDate = date("start_date")
    val endDate = date("end_date")
    val unitReviewStatus = varchar("unit_review_status", 20).default("NOT_REQUIRED")
    val unitReviewedByUserId = uuid("unit_reviewed_by_user_id").references(Users.id).nullable()
    val unitReviewedAt = timestamp("unit_reviewed_at").nullable()
    val topLevelReviewStatus = varchar("top_level_review_status", 20).default("NOT_REQUIRED")
    val topLevelReviewedByUserId = uuid("top_level_reviewed_by_user_id").references(Users.id).nullable()
    val topLevelReviewedAt = timestamp("top_level_reviewed_at").nullable()
    val pickupAt = timestamp("pickup_at").nullable()
    val pickupLocationId = uuid("pickup_location_id").references(Locations.id).nullable()
    val pickupProposalStatus = varchar("pickup_proposal_status", 20).default("NONE")
    val pickupProposedAt = timestamp("pickup_proposed_at").nullable()
    val pickupProposedByUserId = uuid("pickup_proposed_by_user_id").references(Users.id).nullable()
    val pickupRespondedAt = timestamp("pickup_responded_at").nullable()
    val pickupRespondedByUserId = uuid("pickup_responded_by_user_id").references(Users.id).nullable()
    val returnAt = timestamp("return_at").nullable()
    val returnLocationId = uuid("return_location_id").references(Locations.id).nullable()
    val returnProposalStatus = varchar("return_proposal_status", 20).default("NONE")
    val returnProposedAt = timestamp("return_proposed_at").nullable()
    val returnProposedByUserId = uuid("return_proposed_by_user_id").references(Users.id).nullable()
    val returnRespondedAt = timestamp("return_responded_at").nullable()
    val returnRespondedByUserId = uuid("return_responded_by_user_id").references(Users.id).nullable()
    val status = varchar("status", 20).default("PENDING")
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
