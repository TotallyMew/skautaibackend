package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table

object EventInventoryBuckets : Table("event_inventory_buckets") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id").references(Events.id)
    val pastovykleId = uuid("pastovykle_id").references(Pastovykles.id).nullable()
    val locationId = uuid("location_id").references(Locations.id).nullable()
    val name = varchar("name", 120)
    val type = varchar("type", 30)
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}
