package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Events : Table("events") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val name = varchar("name", 200)
    val type = varchar("type", 100)
    val customTypeLabel = varchar("custom_type_label", 100).nullable()
    val startDate = date("start_date")
    val endDate = date("end_date")
    val locationId = uuid("location_id").references(Locations.id).nullable()
    val organizationalUnitId = uuid("organizational_unit_id")
        .references(OrganizationalUnits.id).nullable()
    val createdByUserId = uuid("created_by_user_id").references(Users.id).nullable()
    val status = varchar("status", 20).default("PLANNING")
    val inventoryBudgetAmount = decimal("inventory_budget_amount", 10, 2).nullable()
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
