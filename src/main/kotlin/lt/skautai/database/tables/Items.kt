package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Items : Table("items") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val custodianId = uuid("custodian_id").references(OrganizationalUnits.id).nullable()
    val origin = varchar("origin", 30).default("UNIT_ACQUIRED")
    val name = varchar("name", 200)
    val description = text("description").nullable()
    val type = varchar("type", 20)
    val category = varchar("category", 30)
    val condition = varchar("condition", 20).default("GOOD")
    val quantity = integer("quantity").default(1)
    val locationId = uuid("location_id").references(Locations.id).nullable()
    val temporaryStorageLabel = varchar("temporary_storage_label", 255).nullable()
    val sourceSharedItemId = uuid("source_shared_item_id").references(id).nullable()
    val responsibleUserId = uuid("responsible_user_id").references(Users.id).nullable()
    val createdByUserId = uuid("created_by_user_id").references(Users.id).nullable()
    val photoUrl = text("photo_url").nullable()
    val purchaseDate = date("purchase_date").nullable()
    val purchasePrice = decimal("purchase_price", 10, 2).nullable()
    val notes = text("notes").nullable()
    val status = varchar("status", 20).default("ACTIVE")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
