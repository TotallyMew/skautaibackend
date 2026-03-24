package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date

object StovyklaDetails : Table("stovykla_details") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id").references(Events.id).uniqueIndex()
    val registrationDeadline = date("registration_deadline").nullable()
    val expectedParticipants = integer("expected_participants").nullable()
    val actualParticipants = integer("actual_participants").nullable()

    override val primaryKey = PrimaryKey(id)
}