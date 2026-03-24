package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ItemAttachments : Table("item_attachments") {
    val id = uuid("id").autoGenerate()
    val itemId = uuid("item_id").references(Items.id)
    val fileUrl = text("file_url")
    val fileType = varchar("file_type", 20).nullable()
    val uploadedByUserId = uuid("uploaded_by_user_id").references(Users.id).nullable()
    val uploadedAt = timestamp("uploaded_at")

    override val primaryKey = PrimaryKey(id)
}