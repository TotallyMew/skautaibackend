package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object ImportColumnMappings : Table("import_column_mappings") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val name = varchar("name", 100)
    val createdByUserId = uuid("created_by_user_id").references(Users.id).nullable()
    val columnMappings = jsonb<JsonElement>("column_mappings", Json)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}