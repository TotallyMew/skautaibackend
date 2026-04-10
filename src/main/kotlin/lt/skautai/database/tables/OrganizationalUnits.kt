package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object OrganizationalUnits : Table("organizational_units") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val parentId = uuid("parent_id").references(id).nullable()
    val name = varchar("name", 100)
    val type = varchar("type", 30)
    val acceptedRankId = uuid("accepted_rank_id").references(Roles.id).nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}