package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object UserTuntasMemberships : Table("user_tuntas_memberships") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(Users.id)
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val joinedAt = timestamp("joined_at")
    val leftAt = timestamp("left_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(userId, tuntasId)
    }
}