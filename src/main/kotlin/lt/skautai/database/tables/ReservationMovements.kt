package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ReservationMovements : Table("reservation_movements") {
    val id = uuid("id").autoGenerate()
    val reservationGroupId = uuid("reservation_group_id")
    val itemId = uuid("item_id").references(Items.id)
    val locationId = uuid("location_id").references(Locations.id).nullable()
    val type = varchar("type", 20)
    val quantity = integer("quantity")
    val performedByUserId = uuid("performed_by_user_id").references(Users.id)
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
