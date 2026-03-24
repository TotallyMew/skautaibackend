package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table

object Pastovykles : Table("pastovykles") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id").references(Events.id)
    val name = varchar("name", 100)
    val responsibleUserId = uuid("responsible_user_id").references(Users.id).nullable()
    val ageGroup = varchar("age_group", 30).nullable()
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}