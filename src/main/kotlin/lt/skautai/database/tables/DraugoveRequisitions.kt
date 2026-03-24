package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object DraugoveRequisitions : Table("draugove_requisitions") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val organizationalUnitId = uuid("organizational_unit_id")
        .references(OrganizationalUnits.id)
    val eventId = uuid("event_id").references(Events.id).nullable()
    val createdByUserId = uuid("created_by_user_id").references(Users.id)
    val reviewedByUserId = uuid("reviewed_by_user_id").references(Users.id).nullable()
    val status = varchar("status", 30).default("DRAFT")
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}