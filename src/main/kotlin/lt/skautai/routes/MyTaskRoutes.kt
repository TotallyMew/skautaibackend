package lt.skautai.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.services.MyTaskService
import java.util.UUID

fun Route.myTaskRoutes(service: MyTaskService, apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        route("$apiPrefix/tasks/my") {
            get {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try {
                    UUID.fromString(tuntasId)
                } catch (_: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                service.getMyTasks(tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "Failed to fetch tasks")) }
            }
        }
    }
}
