package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.CreateReservationRequest
import lt.skautai.models.requests.ReservationMovementRequest
import lt.skautai.models.requests.ReviewReservationRequest
import lt.skautai.models.requests.UpdateReservationPickupRequest
import lt.skautai.models.requests.UpdateReservationReturnTimeRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.plugins.resolveUserPermissions
import lt.skautai.models.responses.ReservationResponse
import lt.skautai.services.FirebaseNotificationService
import lt.skautai.services.NotificationRecipientService
import lt.skautai.services.PermissionContextService
import lt.skautai.services.ReservationService
import java.util.*

fun Route.reservationRoutes(
    reservationService: ReservationService,
    firebaseNotificationService: FirebaseNotificationService,
    notificationRecipientService: NotificationRecipientService,
    apiPrefix: String = "/api"
) {
    authenticate("auth-jwt") {
        route("$apiPrefix/reservations") {

            get {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!PermissionContextService.resolve(userId, tuntasUUID).has("reservations.view")) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))

                val itemId = call.request.queryParameters["itemId"]?.let {
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }
                val status = call.request.queryParameters["status"]
                val updatedAfter = call.request.queryParameters["updatedAfter"]?.let(::parseInstantOrNull)
                val limit = call.request.queryParameters["limit"]?.let { raw ->
                    raw.toIntOrNull()
                        ?.takeIf { it in 1..200 }
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("limit must be between 1 and 200"))
                }
                val offset = call.request.queryParameters["offset"]?.let { raw ->
                    raw.toIntOrNull()
                        ?.takeIf { it >= 0 }
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("offset must be 0 or greater"))
                } ?: 0

                val userPerms = resolveUserPermissions(userId, tuntasUUID)
                val canViewAll = userPerms.any {
                    it.permissionName == "reservations.approve" && it.scope == "ALL"
                }
                val approvableUnitIds = userPerms
                    .filter { it.permissionName == "reservations.approve" && it.scope != "ALL" }
                    .flatMap { it.userOrgUnitIds }
                    .distinct()

                reservationService.getReservations(
                    tuntasId = tuntasUUID,
                    userId = userId,
                    canViewAll = canViewAll,
                    approvableUnitIds = approvableUnitIds,
                    itemId = itemId,
                    status = status,
                    updatedAfter = updatedAfter,
                    limit = limit,
                    offset = offset
                )
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "Failed to fetch reservations")) }
            }

            get("availability") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
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

                val resolvedPerms = resolveUserPermissions(userId, tuntasUUID)
                val canApproveTopLevel = resolvedPerms.any {
                    it.permissionName == "reservations.approve" && it.scope == "ALL"
                }
                val userUnitIds = resolvedPerms.flatMap { it.userOrgUnitIds }.toSet()

                reservationService.getAvailability(tuntasUUID, userId, startDate, endDate, canApproveTopLevel, userUnitIds)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to fetch reservation availability")) }
            }

            get("{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!PermissionContextService.resolve(userId, tuntasUUID).has("reservations.view")) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))

                val reservationId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reservation ID required"))
                val reservationUUID = try { UUID.fromString(reservationId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid reservation ID"))
                }

                val resolvedPerms = resolveUserPermissions(userId, tuntasUUID)
                val canViewAll = resolvedPerms.any {
                    it.permissionName == "reservations.approve" && it.scope == "ALL"
                }
                val approvableUnitIds = resolvedPerms
                    .filter { it.permissionName == "reservations.approve" && it.scope != "ALL" }
                    .flatMap { it.userOrgUnitIds }
                    .toSet()

                reservationService.getReservation(reservationUUID, tuntasUUID, userId, canViewAll, approvableUnitIds)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure {
                        val status = if (it.message == "Reservation not found") HttpStatusCode.NotFound else HttpStatusCode.Forbidden
                        call.respond(status, ErrorResponse(it.message ?: "Reservation not found"))
                    }
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

                val request = call.receiveValidated<CreateReservationRequest>()
                val resolvedPerms = resolveUserPermissions(userId, tuntasUUID)
                val canApproveTopLevel = resolvedPerms.any {
                    it.permissionName == "reservations.approve" && it.scope == "ALL"
                }
                val approvableUnitIds = resolvedPerms
                    .filter { it.permissionName == "reservations.approve" && it.scope != "ALL" }
                    .flatMap { it.userOrgUnitIds }
                    .toSet()
                val userUnitIds = resolvedPerms.flatMap { it.userOrgUnitIds }.toSet()

                reservationService.createReservation(
                    tuntasUUID,
                    userId,
                    request,
                    canApproveTopLevel,
                    approvableUnitIds,
                    userUnitIds
                )
                    .onSuccess {
                        firebaseNotificationService.sendReservationAwaitingApprovalNotifications(
                            reservation = it,
                            recipients = notificationRecipientService,
                            excludeUserId = userId
                        )
                        call.respond(HttpStatusCode.Created, it)
                    }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create reservation")) }
            }

            post("{id}/unit-review") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }
                val reservationId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reservation ID required"))
                val reservationUUID = try { UUID.fromString(reservationId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid reservation ID"))
                }
                val resolvedPerms = resolveUserPermissions(userId, tuntasUUID)
                val canApproveTopLevel = resolvedPerms.any {
                    it.permissionName == "reservations.approve" && it.scope == "ALL"
                }
                val approvableUnitIds = resolvedPerms
                    .filter { it.permissionName == "reservations.approve" && it.scope != "ALL" }
                    .flatMap { it.userOrgUnitIds }
                    .toSet()
                if (!canApproveTopLevel && approvableUnitIds.isEmpty()) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val request = call.receiveValidated<ReviewReservationRequest>()
                reservationService.reviewReservation(
                    reservationUUID,
                    tuntasUUID,
                    userId,
                    "unit",
                    request,
                    canApproveTopLevel,
                    approvableUnitIds
                )
                    .onSuccess {
                        firebaseNotificationService.sendReservationReviewNotification(it)
                        firebaseNotificationService.sendReservationAwaitingApprovalNotifications(
                            reservation = it,
                            recipients = notificationRecipientService,
                            excludeUserId = userId
                        )
                        call.respond(HttpStatusCode.OK, it)
                    }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to review reservation")) }
            }

            post("{id}/top-level-review") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }
                val reservationId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reservation ID required"))
                val reservationUUID = try { UUID.fromString(reservationId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid reservation ID"))
                }
                val resolvedPerms = resolveUserPermissions(userId, tuntasUUID)
                val canApproveTopLevel = resolvedPerms.any {
                    it.permissionName == "reservations.approve" && it.scope == "ALL"
                }
                if (!canApproveTopLevel) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }
                val request = call.receiveValidated<ReviewReservationRequest>()
                reservationService.reviewReservation(
                    reservationUUID,
                    tuntasUUID,
                    userId,
                    "top-level",
                    request,
                    canApproveTopLevel = canApproveTopLevel,
                    approvableUnitIds = emptySet()
                )
                    .onSuccess {
                        firebaseNotificationService.sendReservationReviewNotification(it)
                        call.respond(HttpStatusCode.OK, it)
                    }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to review reservation")) }
            }

            get("{id}/movements") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }
                if (!PermissionContextService.resolve(userId, tuntasUUID).has("reservations.view")) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val reservationId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reservation ID required"))
                val reservationUUID = try { UUID.fromString(reservationId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid reservation ID"))
                }
                val resolvedPerms = resolveUserPermissions(userId, tuntasUUID)
                val canViewAll = resolvedPerms.any {
                    it.permissionName == "reservations.approve" && it.scope == "ALL"
                }
                val approvableUnitIds = resolvedPerms
                    .filter { it.permissionName == "reservations.approve" && it.scope != "ALL" }
                    .flatMap { it.userOrgUnitIds }
                    .toSet()
                reservationService.getMovements(reservationUUID, tuntasUUID, userId, canViewAll, approvableUnitIds)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to fetch movements")) }
            }

            post("{id}/issue") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }
                val reservationId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reservation ID required"))
                val reservationUUID = try { UUID.fromString(reservationId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid reservation ID"))
                }
                val resolvedPerms = resolveUserPermissions(userId, tuntasUUID)
                val canApproveTopLevel = resolvedPerms.any {
                    it.permissionName == "reservations.approve" && it.scope == "ALL"
                }
                val approvableUnitIds = resolvedPerms
                    .filter { it.permissionName == "reservations.approve" && it.scope != "ALL" }
                    .flatMap { it.userOrgUnitIds }
                    .toSet()
                if (!canApproveTopLevel && approvableUnitIds.isEmpty()) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }
                val request = call.receiveValidated<ReservationMovementRequest>()
                reservationService.recordMovement(
                    reservationUUID,
                    tuntasUUID,
                    userId,
                    "ISSUE",
                    request,
                    canApproveTopLevel,
                    approvableUnitIds
                )
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to issue items")) }
            }

            post("{id}/return") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }
                val reservationId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reservation ID required"))
                val reservationUUID = try { UUID.fromString(reservationId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid reservation ID"))
                }
                val resolvedPerms = resolveUserPermissions(userId, tuntasUUID)
                val canApproveTopLevel = resolvedPerms.any {
                    it.permissionName == "reservations.approve" && it.scope == "ALL"
                }
                val approvableUnitIds = resolvedPerms
                    .filter { it.permissionName == "reservations.approve" && it.scope != "ALL" }
                    .flatMap { it.userOrgUnitIds }
                    .toSet()
                if (!canApproveTopLevel && approvableUnitIds.isEmpty()) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }
                val request = call.receiveValidated<ReservationMovementRequest>()
                reservationService.recordMovement(
                    reservationUUID,
                    tuntasUUID,
                    userId,
                    "RETURN",
                    request,
                    canApproveTopLevel,
                    approvableUnitIds
                )
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to return items")) }
            }

            post("{id}/mark-returned") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }
                if (!PermissionContextService.resolve(userId, tuntasUUID).has("reservations.view")) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val reservationId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reservation ID required"))
                val reservationUUID = try { UUID.fromString(reservationId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid reservation ID"))
                }
                val request = call.receiveValidated<ReservationMovementRequest>()
                reservationService.recordMovement(
                    reservationUUID,
                    tuntasUUID,
                    userId,
                    "RETURN_MARKED",
                    request,
                    canApproveTopLevel = false,
                    approvableUnitIds = emptySet()
                )
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to mark items as returned")) }
            }

            put("{id}/pickup-time") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }
                if (!PermissionContextService.resolve(userId, tuntasUUID).has("reservations.view")) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val reservationId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reservation ID required"))
                val reservationUUID = try { UUID.fromString(reservationId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid reservation ID"))
                }
                val resolvedPerms = resolveUserPermissions(userId, tuntasUUID)
                val canManageTopLevel = resolvedPerms.any {
                    it.permissionName == "reservations.approve" && it.scope == "ALL"
                }
                val approvableUnitIds = resolvedPerms
                    .filter { it.permissionName == "reservations.approve" && it.scope != "ALL" }
                    .flatMap { it.userOrgUnitIds }
                    .toSet()
                val request = call.receiveValidated<UpdateReservationPickupRequest>()
                reservationService.updatePickupTime(reservationUUID, tuntasUUID, userId, canManageTopLevel, approvableUnitIds, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to update pickup time")) }
            }

            put("{id}/return-time") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }
                if (!PermissionContextService.resolve(userId, tuntasUUID).has("reservations.view")) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val reservationId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reservation ID required"))
                val reservationUUID = try { UUID.fromString(reservationId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid reservation ID"))
                }
                val resolvedPerms = resolveUserPermissions(userId, tuntasUUID)
                val canManageTopLevel = resolvedPerms.any {
                    it.permissionName == "reservations.approve" && it.scope == "ALL"
                }
                val approvableUnitIds = resolvedPerms
                    .filter { it.permissionName == "reservations.approve" && it.scope != "ALL" }
                    .flatMap { it.userOrgUnitIds }
                    .toSet()
                val request = call.receiveValidated<UpdateReservationReturnTimeRequest>()
                reservationService.updateReturnTime(reservationUUID, tuntasUUID, userId, canManageTopLevel, approvableUnitIds, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to update return time")) }
            }

            put("{id}/status") {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Use unit-review or top-level-review for reservation approvals")
                )
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
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Reservation cancelled")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to cancel reservation")) }
            }
        }
    }
}

private fun parseInstantOrNull(value: String): kotlinx.datetime.Instant? = try {
    kotlinx.datetime.Instant.parse(value)
} catch (_: Exception) {
    null
}

private fun FirebaseNotificationService.sendReservationAwaitingApprovalNotifications(
    reservation: ReservationResponse,
    recipients: NotificationRecipientService,
    excludeUserId: UUID
) {
    val targetUsers = when {
        reservation.unitReviewStatus == "PENDING" -> {
            val unitId = reservation.requestingUnitId?.let(UUID::fromString) ?: return
            recipients.usersWithPermission(
                tuntasId = UUID.fromString(reservation.tuntasId),
                permissionName = "reservations.approve",
                organizationalUnitId = unitId,
                excludeUserId = excludeUserId
            )
        }
        reservation.topLevelReviewStatus == "PENDING" -> {
            recipients.usersWithPermission(
                tuntasId = UUID.fromString(reservation.tuntasId),
                permissionName = "reservations.approve",
                excludeUserId = excludeUserId
            )
        }
        else -> emptyList()
    }

    targetUsers.forEach { userId ->
        sendToUser(
            userId = userId,
            title = "Nauja rezervacija laukia patvirtinimo",
            body = "Rezervacija \"${reservation.title}\" laukia jusu patvirtinimo.",
            data = mapOf(
                "resource" to "reservations",
                "reservationId" to reservation.id,
                "tuntasId" to reservation.tuntasId,
                "status" to reservation.status
            )
        )
    }
}

private fun FirebaseNotificationService.sendReservationReviewNotification(reservation: ReservationResponse) {
    val status = reservation.status.uppercase()
    if (status !in setOf("APPROVED", "REJECTED")) return
    val title = if (status == "APPROVED") "Rezervacija patvirtinta" else "Rezervacija atmesta"
    val body = if (status == "APPROVED") {
        "Jūsų rezervacija „${reservation.title}“ buvo patvirtinta."
    } else {
        "Jūsų rezervacija „${reservation.title}“ buvo atmesta."
    }
    sendToUser(
        userId = UUID.fromString(reservation.reservedByUserId),
        title = title,
        body = body,
        data = mapOf(
            "resource" to "reservations",
            "reservationId" to reservation.id,
            "tuntasId" to reservation.tuntasId,
            "status" to reservation.status
        )
    )
}
