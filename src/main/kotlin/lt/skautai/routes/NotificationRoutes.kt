package lt.skautai.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.services.NotificationService
import java.util.UUID

fun Route.notificationRoutes(notificationService: NotificationService, apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        route("$apiPrefix/notifications") {
            get {
                val userId = call.userId() ?: return@get
                val unreadOnly = call.request.queryParameters["unreadOnly"]?.toBooleanStrictOrNull() ?: false
                call.respond(HttpStatusCode.OK, notificationService.listForUser(userId, unreadOnly))
            }

            post("{id}/read") {
                val userId = call.userId() ?: return@post
                val notificationId = call.parameters["id"]
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid notification ID"))

                if (notificationService.markRead(notificationId, userId)) {
                    call.respond(HttpStatusCode.OK, MessageResponse("Notification marked as read"))
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Notification not found"))
                }
            }

            post("read-all") {
                val userId = call.userId() ?: return@post
                val count = notificationService.markAllRead(userId)
                call.respond(HttpStatusCode.OK, MessageResponse("$count notifications marked as read"))
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.userId(): UUID? {
    val principal = principal<JWTPrincipal>()
        ?: return respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated")).let { null }
    return principal.getClaim("userId", String::class)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token")).let { null }
}
