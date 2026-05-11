package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ItemConditionLog : Table("item_condition_log") {
    val id = uuid("id").autoGenerate()
    val itemId = uuid("item_id").references(Items.id)
    val previousCondition = varchar("previous_condition", 30).nullable()
    val newCondition = varchar("new_condition", 30)
    val reportedByUserId = uuid("reported_by_user_id").references(Users.id).nullable()
    val reportedAt = timestamp("reported_at")
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}
