package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ItemCheckSessions : Table("item_check_sessions") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val contextType = varchar("context_type", 30)
    val eventId = uuid("event_id").references(Events.id).nullable()
    val scopeCustodianId = uuid("scope_custodian_id").references(OrganizationalUnits.id).nullable()
    val scopeType = varchar("scope_type", 100).nullable()
    val scopeCategory = varchar("scope_category", 100).nullable()
    val scopeSharedOnly = bool("scope_shared_only").default(false)
    val scopePersonalOwnerUserId = uuid("scope_personal_owner_user_id").references(Users.id).nullable()
    val startedByUserId = uuid("started_by_user_id").references(Users.id)
    val completedByUserId = uuid("completed_by_user_id").references(Users.id).nullable()
    val status = varchar("status", 20).default("OPEN")
    val scopeItemCount = integer("scope_item_count").default(0)
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")
    val completedAt = timestamp("completed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
