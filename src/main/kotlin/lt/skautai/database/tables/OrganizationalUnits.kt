package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object OrganizationalUnits : Table("organizational_units") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val name = varchar("name", 100)
    val type = varchar("type", 40)
    val subtype = varchar("subtype", 20).nullable()
    val acceptedRankId = uuid("accepted_rank_id").references(Roles.id).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
