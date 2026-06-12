package lt.skautai.services

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.datetime.Clock

@Serializable
data class LiveEvent(
    val id: String = UUID.randomUUID().toString(),
    val tuntasId: String,
    val actorUserId: String? = null,
    val resource: String,
    val action: String,
    val path: String,
    val occurredAt: String = Clock.System.now().toString()
)

object LiveEventBus {
    private val flows = ConcurrentHashMap<UUID, MutableSharedFlow<LiveEvent>>()

    fun eventsFor(tuntasId: UUID): Flow<LiveEvent> = flowFor(tuntasId)

    fun publish(event: LiveEvent) {
        val tuntasId = runCatching { UUID.fromString(event.tuntasId) }.getOrNull() ?: return
        flowFor(tuntasId).tryEmit(event)
    }

    private fun flowFor(tuntasId: UUID): MutableSharedFlow<LiveEvent> =
        flows.computeIfAbsent(tuntasId) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 128,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }
}
