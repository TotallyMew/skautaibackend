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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.services.LiveEventBus
import lt.skautai.services.PermissionContextService
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

                        coroutineScope {
                            val heartbeat = launch {
                                while (true) {
                                    delay(25_000)
                                    write(": heartbeat\n\n")
                                    flush()
                                }
                            }

                            try {
                                LiveEventBus.eventsFor(tuntasId)
                                    .onEach { event ->
                                        write("id: ${event.id}\n")
                                        write("event: ${event.resource}\n")
                                        write("data: ${Json.encodeToString(event)}\n\n")
                                        flush()
                                    }
                                    .collect()
                            } finally {
                                heartbeat.cancel()
                            }
                        }
                    }
                }.onFailure { error ->
                    if (!error.isSseClientDisconnect()) throw error
                }
            }
        }
    }
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
