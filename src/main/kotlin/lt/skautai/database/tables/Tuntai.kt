package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Tuntai : Table("tuntai") {
    val id = uuid("id").autoGenerate()
    val name = varchar("name", 100).uniqueIndex()
    val krastas = varchar("krastas", 100).nullable()
    val contactEmail = varchar("contact_email", 255).nullable()
    val status = varchar("status", 20).default("PENDING")
    val approvedBySuperAdminId = uuid("approved_by_super_admin_id")
        .references(SuperAdmins.id).nullable()
    val createdAt = timestamp("created_at")
    val approvedAt = timestamp("approved_at").nullable()
    val rejectedAt = timestamp("rejected_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
