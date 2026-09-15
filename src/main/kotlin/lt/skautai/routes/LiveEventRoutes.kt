package lt.skautai.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.services.LiveEventBus
import lt.skautai.services.LiveEvent
import lt.skautai.services.PermissionContextService
import lt.skautai.plugins.isAccessSessionActive
import lt.skautai.plugins.ResolvedPermission
import java.util.UUID

fun Route.liveEventRoutes(apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        route("$apiPrefix/live") {
            get("/events") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))

                val permissions = PermissionContextService.resolve(userId, tuntasId)
                if (permissions.permissions.isEmpty()) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))
                }

                runCatching {
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        write("retry: 5000\n\n")
                        flush()

                        val heartbeat = flow<LiveEvent?> {
                            while (true) { delay(5_000); emit(null) }
                        }
                        // One collector writes the stream; recheck before every event and while idle.
                        merge(heartbeat, LiveEventBus.eventsFor(tuntasId).map { it as LiveEvent? })
                            .takeWhile { liveAccessStillValid(principal.payload, userId, tuntasId, permissions.permissions) }
                            .collect { event ->
                                if (event == null) write(": heartbeat\n\n")
                                else {
                                    write("id: ${event.id}\n")
                                    write("event: ${event.resource}\n")
                                    write("data: ${Json.encodeToString(event)}\n\n")
                                }
                                flush()
                            }
                    }
                }.onFailure { error ->
                    if (!error.isSseClientDisconnect()) throw error
                }
            }
        }
    }
}

internal fun liveAccessStillValid(
    payload: com.auth0.jwt.interfaces.Payload,
    userId: UUID,
    tuntasId: UUID,
    initialPermissions: List<ResolvedPermission>
): Boolean = isAccessSessionActive(payload) &&
    PermissionContextService.resolve(userId, tuntasId).permissions.let {
        it.isNotEmpty() && it.toSet() == initialPermissions.toSet()
    }

private fun Throwable.isSseClientDisconnect(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val name = current::class.simpleName.orEmpty()
        if (
            name == "ChannelWriteException" ||
            name == "ClosedWriteChannelException" ||
            name == "ClosedByteChannelException" ||
            name == "StacklessClosedChannelException"
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
