package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object SeniorUnitAccessAudit : Table("senior_unit_access_audit") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val unitId = uuid("unit_id").references(OrganizationalUnits.id)
    val actorUserId = uuid("actor_user_id").references(Users.id)
    val action = varchar("action", 50)
    val accessMode = varchar("access_mode", 20)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
