package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.CreateReservationRequest
import lt.skautai.models.requests.UpdateReservationStatusRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.plugins.resolveUserPermissions
import lt.skautai.services.ReservationService
import java.util.*

fun Route.reservationRoutes(reservationService: ReservationService) {
    authenticate("auth-jwt") {
        route("/api/reservations") {

            get {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("reservations.view", tuntasUUID)) return@get

                val itemId = call.request.queryParameters["itemId"]?.let {
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }
                val status = call.request.queryParameters["status"]

                val userPerms = resolveUserPermissions(userId, tuntasUUID)
                val isAdmin = userPerms.any {
                    it.permissionName == "reservations.approve" && it.scope == "ALL"
                }
                val unitIds = if (!isAdmin)
                    userPerms.firstOrNull()?.userOrgUnitIds?.toList() ?: emptyList()
                else emptyList()

                reservationService.getReservations(tuntasUUID, userId, isAdmin, unitIds, itemId, status)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "Failed to fetch reservations")) }
            }

            get("availability") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("reservations.create", tuntasUUID)) return@get

                val startDate = call.request.queryParameters["startDate"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("startDate is required"))
                val endDate = call.request.queryParameters["endDate"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("endDate is required"))

                reservationService.getAvailability(tuntasUUID, startDate, endDate)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to fetch reservation availability")) }
            }

            get("{id}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("reservations.view", tuntasUUID)) return@get

                val reservationId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reservation ID required"))
                val reservationUUID = try { UUID.fromString(reservationId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid reservation ID"))
                }

                reservationService.getReservation(reservationUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Reservation not found")) }
            }

            post {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("reservations.create", tuntasUUID)) return@post

                val request = call.receive<CreateReservationRequest>()

                reservationService.createReservation(tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create reservation")) }
            }

            put("{id}/status") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("reservations.approve", tuntasUUID)) return@put

                val reservationId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reservation ID required"))
                val reservationUUID = try { UUID.fromString(reservationId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid reservation ID"))
                }

                // Tuntininkas/pavaduotojas (ALL-scope approvers) cannot approve their own reservation
                val resolvedPerms = resolveUserPermissions(userId, tuntasUUID)
                val hasAllScopeApprove = resolvedPerms.any { it.permissionName == "reservations.approve" && it.scope == "ALL" }
                if (hasAllScopeApprove) {
                    val reservedByUserId = reservationService.getReservationOwner(reservationUUID, tuntasUUID)
                    if (reservedByUserId == userId) {
                        return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cannot approve your own reservation"))
                    }
                }

                val request = call.receive<UpdateReservationStatusRequest>()

                reservationService.updateReservationStatus(reservationUUID, tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to update reservation status")) }
            }

            delete("{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("reservations.create", tuntasUUID)) return@delete

                val reservationId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reservation ID required"))
                val reservationUUID = try { UUID.fromString(reservationId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid reservation ID"))
                }

                reservationService.cancelReservation(reservationUUID, tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Reservation cancelled")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to cancel reservation")) }
            }
        }
    }
}
