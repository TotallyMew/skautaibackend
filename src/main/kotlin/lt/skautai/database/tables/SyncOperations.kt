package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object SyncOperations : Table("sync_operations") {
    val id = uuid("id").autoGenerate()
    val operationId = uuid("operation_id").uniqueIndex()
    val operationType = varchar("operation_type", 10)
    val entityType = varchar("entity_type", 50)
    val entityId = uuid("entity_id")
    val payload = jsonb<JsonElement>("payload", Json)
    val userId = uuid("user_id").references(Users.id).nullable()
    val deviceId = uuid("device_id").references(Devices.id).nullable()
    val clientTimestamp = timestamp("client_timestamp")
    val serverTimestamp = timestamp("server_timestamp").nullable()
    val status = varchar("status", 20).default("PENDING")
    val conflictNotes = text("conflict_notes").nullable()

    override val primaryKey = PrimaryKey(id)
}