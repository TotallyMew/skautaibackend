package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object InventoryKits : Table("inventory_kits") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val custodianId = uuid("custodian_id").references(OrganizationalUnits.id).nullable()
    val name = varchar("name", 200)
    val description = text("description").nullable()
    val locationId = uuid("location_id").references(Locations.id).nullable()
    val temporaryStorageLabel = varchar("temporary_storage_label", 255).nullable()
    val responsibleUserId = uuid("responsible_user_id").references(Users.id).nullable()
    val createdByUserId = uuid("created_by_user_id").references(Users.id).nullable()
    val status = varchar("status", 20).default("ACTIVE")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
