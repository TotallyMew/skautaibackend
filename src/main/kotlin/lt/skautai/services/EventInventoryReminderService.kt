package lt.skautai.services

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import lt.skautai.database.tables.EventInventoryItems
import lt.skautai.database.tables.EventInventoryRequests
import lt.skautai.database.tables.Events
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.time.Duration.Companion.hours

class EventInventoryReminderService(
    private val firebaseNotificationService: FirebaseNotificationService
) {
    fun dispatchDueReminders(now: Instant = Clock.System.now()): Int {
        val threshold = now + 24.hours
        val reminders = transaction {
            EventInventoryRequests
                .innerJoin(Events, { EventInventoryRequests.eventId }, { Events.id })
                .innerJoin(EventInventoryItems, { EventInventoryRequests.eventInventoryItemId }, { EventInventoryItems.id })
                .selectAll()
                .where {
                    (EventInventoryRequests.status inList listOf("PENDING", "APPROVED")) and
                        EventInventoryRequests.responsibleUserId.isNotNull() and
                        EventInventoryRequests.dueAt.isNotNull() and
                        EventInventoryRequests.reminderSentAt.isNull()
                }
                .orderBy(EventInventoryRequests.dueAt, SortOrder.ASC)
                .filter { it[EventInventoryRequests.dueAt]!! <= threshold }
                .map {
                    Reminder(
                        requestId = it[EventInventoryRequests.id],
                        eventId = it[EventInventoryRequests.eventId],
                        tuntasId = it[Events.tuntasId],
                        userId = it[EventInventoryRequests.responsibleUserId]!!,
                        eventName = it[Events.name],
                        itemName = it[EventInventoryItems.name],
                        dueAt = it[EventInventoryRequests.dueAt]!!
                    )
                }
        }

        reminders.forEach { reminder ->
            val overdue = reminder.dueAt < now
            firebaseNotificationService.sendToUser(
                userId = reminder.userId,
                title = if (overdue) "Vėluoja renginio poreikis" else "Artėja renginio poreikio terminas",
                body = "${reminder.eventName}: ${reminder.itemName}, terminas ${reminder.dueAt}.",
                data = mapOf(
                    "resource" to "event_inventory_request",
                    "entityId" to reminder.requestId.toString(),
                    "requestId" to reminder.requestId.toString(),
                    "eventId" to reminder.eventId.toString(),
                    "tuntasId" to reminder.tuntasId.toString()
                )
            )
            transaction {
                EventInventoryRequests.update({
                    (EventInventoryRequests.id eq reminder.requestId) and
                        EventInventoryRequests.reminderSentAt.isNull()
                }) {
                    it[reminderSentAt] = now
                }
            }
        }
        return reminders.size
    }

    private data class Reminder(
        val requestId: java.util.UUID,
        val eventId: java.util.UUID,
        val tuntasId: java.util.UUID,
        val userId: java.util.UUID,
        val eventName: String,
        val itemName: String,
        val dueAt: Instant
    )
}
