package lt.skautai.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import lt.skautai.models.requests.CreateLeadershipChangeRequest
import lt.skautai.models.requests.ReviewLeadershipChangeRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.services.LeadershipChangeRequestService
import java.util.UUID

fun Route.leadershipChangeRequestRoutes(service: LeadershipChangeRequestService, apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        route("$apiPrefix/leadership-change-requests") {
            get {
                val tuntasUUID = call.tuntasIdOrRespond() ?: return@get
                val callerUserId = call.callerUserId()
                val status = call.request.queryParameters["status"] ?: "PENDING"

                service.getRequests(tuntasUUID, callerUserId, status)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to fetch leadership change requests")) }
            }

            post("{requestId}/review") {
                val tuntasUUID = call.tuntasIdOrRespond() ?: return@post
                val callerUserId = call.callerUserId()
                val requestId = call.parameters["requestId"]
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID"))
                val request = call.receiveValidated<ReviewLeadershipChangeRequest>()

                service.reviewRequest(requestId, tuntasUUID, callerUserId, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to review leadership change request")) }
            }
        }

        post("$apiPrefix/members/me/leadership-roles/{assignmentId}/resignation-request") {
            val tuntasUUID = call.tuntasIdOrRespond() ?: return@post
            val callerUserId = call.callerUserId()
            val assignmentId = call.parameters["assignmentId"]
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid assignment ID"))
            val request = call.receiveValidated<CreateLeadershipChangeRequest>()

            service.createResignationRequest(callerUserId, assignmentId, tuntasUUID, request)
                .onSuccess { call.respond(HttpStatusCode.Created, it) }
                .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create leadership change request")) }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.tuntasIdOrRespond(): UUID? {
    val tuntasId = request.headers["X-Tuntas-Id"]
        ?: run {
            respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
            return null
        }
    return runCatching { UUID.fromString(tuntasId) }.getOrElse {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
        null
    }
}

private fun io.ktor.server.application.ApplicationCall.callerUserId(): UUID {
    val principal = principal<JWTPrincipal>()!!
    return UUID.fromString(principal.getClaim("userId", String::class))
}
