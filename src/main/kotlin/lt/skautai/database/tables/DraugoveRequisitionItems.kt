package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table

object DraugoveRequisitionItems : Table("draugove_requisition_items") {
    val id = uuid("id").autoGenerate()
    val requisitionId = uuid("requisition_id").references(DraugoveRequisitions.id)
    val itemId = uuid("item_id").references(Items.id).nullable()
    val requestType = varchar("request_type", 30).default("NEW_ITEM")
    val existingItemId = uuid("existing_item_id").references(Items.id).nullable()
    val itemName = varchar("item_name", 200).nullable()
    val itemDescription = text("item_description").nullable()
    val quantityRequested = integer("quantity_requested")
    val quantityApproved = integer("quantity_approved").nullable()
    val rejectionReason = text("rejection_reason").nullable()
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}
