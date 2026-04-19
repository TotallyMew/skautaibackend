package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ItemTransfers : Table("item_transfers") {
    val id = uuid("id").autoGenerate()
    val itemId = uuid("item_id").references(Items.id)
    val fromCustodianId = uuid("from_custodian_id").references(OrganizationalUnits.id).nullable()
    val toCustodianId = uuid("to_custodian_id").references(OrganizationalUnits.id).nullable()
    val initiatedByUserId = uuid("initiated_by_user_id").references(Users.id).nullable()
    val approvedByUserId = uuid("approved_by_user_id").references(Users.id).nullable()
    val notes = text("notes").nullable()
    val status = varchar("status", 20).default("PENDING")
    val createdAt = timestamp("created_at")
    val completedAt = timestamp("completed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}