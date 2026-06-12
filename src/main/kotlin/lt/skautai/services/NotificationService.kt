package lt.skautai.services

import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lt.skautai.database.tables.Notifications
import lt.skautai.models.responses.NotificationListResponse
import lt.skautai.models.responses.NotificationResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class NotificationService {
    fun createForUser(
        userId: UUID,
        title: String,
        body: String,
        data: Map<String, String>
    ): UUID = transaction {
        Notifications.insert {
            it[this.userId] = userId
            it[tuntasId] = data["tuntasId"]?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            it[this.title] = title.take(120)
            it[this.body] = body.take(1000)
            it[resource] = data["resource"]?.take(80)
            it[entityId] = resolveEntityId(data)
            it[this.data] = Json.encodeToString(data)
            it[createdAt] = Clock.System.now()
        } get Notifications.id
    }

    fun listForUser(userId: UUID, unreadOnly: Boolean): NotificationListResponse = transaction {
        var query = Notifications.selectAll()
            .where { Notifications.userId eq userId }

        if (unreadOnly) {
            query = query.andWhere { Notifications.readAt.isNull() }
        }

        val rows = query
            .orderBy(Notifications.createdAt, SortOrder.DESC)
            .limit(100)
            .toList()

        val unreadCount = Notifications
            .select(Notifications.id.count())
            .where {
                (Notifications.userId eq userId) and
                    Notifications.readAt.isNull()
            }
            .single()[Notifications.id.count()]
            .toInt()

        NotificationListResponse(
            notifications = rows.map(::toResponse),
            total = rows.size,
            unreadCount = unreadCount
        )
    }

    fun markRead(notificationId: UUID, userId: UUID): Boolean = transaction {
        Notifications.update({
            (Notifications.id eq notificationId) and
                (Notifications.userId eq userId)
        }) {
            it[readAt] = Clock.System.now()
        } > 0
    }

    fun markAllRead(userId: UUID): Int = transaction {
        Notifications.update({
            (Notifications.userId eq userId) and
                Notifications.readAt.isNull()
        }) {
            it[readAt] = Clock.System.now()
        }
    }

    private fun toResponse(row: ResultRow): NotificationResponse {
        val data = row[Notifications.data]?.let { raw ->
            runCatching { Json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
        }.orEmpty()

        return NotificationResponse(
            id = row[Notifications.id].toString(),
            tuntasId = row[Notifications.tuntasId]?.toString(),
            title = row[Notifications.title],
            body = row[Notifications.body],
            resource = row[Notifications.resource],
            entityId = row[Notifications.entityId]?.toString(),
            data = data,
            readAt = row[Notifications.readAt]?.toString(),
            createdAt = row[Notifications.createdAt].toString()
        )
    }

    private fun resolveEntityId(data: Map<String, String>): UUID? {
        val id = data["entityId"]
            ?: data["reservationId"]
            ?: data["requestId"]
            ?: data["itemId"]
            ?: data["eventId"]
        return id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }
}
