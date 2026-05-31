package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Locations : Table("locations") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val name = varchar("name", 100)
    val visibility = varchar("visibility", 20).default("PUBLIC")
    val parentLocationId = uuid("parent_location_id").references(id).nullable()
    val ownerUserId = uuid("owner_user_id").references(Users.id).nullable()
    val ownerUnitId = uuid("owner_unit_id").references(OrganizationalUnits.id).nullable()
    val address = text("address").nullable()
    val description = text("description").nullable()
    val latitude = decimal("latitude", 9, 6).nullable()
    val longitude = decimal("longitude", 9, 6).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
