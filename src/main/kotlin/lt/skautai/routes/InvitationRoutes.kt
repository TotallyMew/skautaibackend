package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.AcceptInvitationRequest
import lt.skautai.models.requests.CreateInvitationRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.services.InvitationService
import java.util.*

fun Route.invitationRoutes(invitationService: InvitationService, apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        route("$apiPrefix/invitations") {
            post {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated"))

                val userId = try {
                    UUID.fromString(principal.getClaim("userId", String::class))
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                }

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))

                val tuntasUUID = try {
                    UUID.fromString(tuntasId)
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val request = call.receiveValidated<CreateInvitationRequest>()
                val targetOrgUnitId = request.organizationalUnitId?.let {
                    try {
                        UUID.fromString(it)
                    } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid organizational unit ID"))
                    }
                }

                if (!checkPermission("invitations.create", tuntasUUID, targetOrgUnitId)) return@post

                invitationService.createInvitation(userId, tuntasUUID, request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create invitation")) }
            }

            post("/accept") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated"))

                val userId = try {
                    UUID.fromString(principal.getClaim("userId", String::class))
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                }

                val request = call.receiveValidated<AcceptInvitationRequest>()
                invitationService.acceptInvitation(userId, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to accept invitation")) }
            }
        }
    }
}
