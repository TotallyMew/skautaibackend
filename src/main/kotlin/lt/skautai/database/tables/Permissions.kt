package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table

object Permissions : Table("permissions") {
    val id = uuid("id").autoGenerate()
    val name = varchar("name", 100).uniqueIndex()
    val description = text("description").nullable()
    val context = varchar("context", 20)

    override val primaryKey = PrimaryKey(id)
}