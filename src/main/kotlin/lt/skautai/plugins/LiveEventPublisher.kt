package lt.skautai.plugins

import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.services.LiveEvent
import lt.skautai.services.LiveEventBus
import java.util.UUID

fun Application.configureLiveEventPublisher() {
    install(LiveEventPublisher)
}

private val LiveEventPublisher = createApplicationPlugin(name = "LiveEventPublisher") {
    onCallRespond { call, body ->
        val event = call.toLiveEvent(body) ?: return@onCallRespond
        LiveEventBus.publish(event)
    }
}

private fun ApplicationCall.toLiveEvent(body: Any): LiveEvent? {
    if (body is ErrorResponse) return null
    val method = request.httpMethod
    if (method !in mutatingMethods) return null

    val path = request.path()
    if (!path.startsWith("/api/")) return null
    if (path.startsWith("/api/auth/")) return null
    if (path.startsWith("/api/mobile/")) return null
    if (path.startsWith("/api/live/")) return null

    val tuntasId = request.headers["X-Tuntas-Id"]
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: return null
    val actorUserId = principal<JWTPrincipal>()?.getClaim("userId", String::class)

    return LiveEvent(
        tuntasId = tuntasId.toString(),
        actorUserId = actorUserId,
        resource = path.toLiveResource(),
        action = method.value.lowercase(),
        path = path
    )
}

private fun String.toLiveResource(): String = when {
    startsWith("/api/items") -> "items"
    startsWith("/api/reservations") -> "reservations"
    startsWith("/api/inventory-requests") -> "bendras_requests"
    startsWith("/api/requisitions") -> "requisitions"
    startsWith("/api/events") -> "events"
    startsWith("/api/locations") -> "locations"
    startsWith("/api/members") -> "members"
    startsWith("/api/units") || startsWith("/api/organizational-units") -> "organizational_units"
    startsWith("/api/invitations") -> "invitations"
    startsWith("/api/roles") -> "roles"
    else -> "general"
}

private val mutatingMethods = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Patch)
