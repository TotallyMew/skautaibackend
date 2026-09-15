package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object StoredUploads : Table("stored_uploads") {
    val id = uuid("id")
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val uploaderId = uuid("uploader_id").references(Users.id).nullable()
    val fileUrl = text("file_url").uniqueIndex()
    val kind = varchar("kind", 16)
    val contentType = varchar("content_type", 100)
    val byteSize = long("byte_size")
    val state = varchar("state", 16)
    val createdAt = timestamp("created_at")
    val attachedAt = timestamp("attached_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

