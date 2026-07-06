package lt.skautai.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import lt.skautai.models.requests.RegisterDeviceRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.services.DeviceService
import lt.skautai.services.FirebaseNotificationService
import java.util.UUID

fun Route.deviceRoutes(
    deviceService: DeviceService,
    firebaseNotificationService: FirebaseNotificationService,
    apiPrefix: String = "/api"
) {
    authenticate("auth-jwt") {
        route("$apiPrefix/devices") {
            post("/register") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val request = call.receiveValidated<RegisterDeviceRequest>()

                deviceService.registerDevice(userId, request)
                    .onSuccess {
                        call.respond(HttpStatusCode.OK, MessageResponse("Device registered"))
                    }
                    .onFailure {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to register device"))
                    }
            }

            delete("/register") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val request = call.receiveValidated<RegisterDeviceRequest>()

                deviceService.unregisterDevice(userId, request.deviceToken)
                    .onSuccess {
                        call.respond(HttpStatusCode.OK, MessageResponse("Device unregistered"))
                    }
                    .onFailure {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to unregister device"))
                    }
            }

            post("/test-notification") {
                if (!notificationsTestEnabled()) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
                }
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.request.headers["X-Tuntas-Id"].orEmpty()

                firebaseNotificationService.sendToUser(
                    userId = userId,
                    title = "Testinis pranešimas",
                    body = "Firebase pranešimai veikia.",
                    data = mapOf(
                        "resource" to "general",
                        "entityId" to "test",
                        "tuntasId" to tuntasId
                    )
                )
                call.respond(HttpStatusCode.OK, MessageResponse("Test notification queued"))
            }
        }
    }
}

private fun notificationsTestEnabled(): Boolean =
    (System.getProperty("NOTIFICATIONS_TEST_ENABLED") ?: System.getenv("NOTIFICATIONS_TEST_ENABLED"))
        ?.equals("true", ignoreCase = true) == true
