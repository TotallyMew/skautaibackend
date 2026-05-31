package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object SyncTombstones : Table("sync_tombstones") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val resourceType = varchar("resource_type", 50)
    val resourceId = uuid("resource_id")
    val deletedAt = timestamp("deleted_at")

    override val primaryKey = PrimaryKey(id)
}
