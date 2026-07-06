package lt.skautai.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import lt.skautai.models.requests.CreateRequisitionRequest
import lt.skautai.models.requests.AddRequisitionToInventoryRequest
import lt.skautai.models.requests.RequisitionTopLevelReviewRequest
import lt.skautai.models.requests.RequisitionUnitReviewRequest
import lt.skautai.models.requests.RequisitionMarkPurchasedRequest
import lt.skautai.models.responses.RequisitionResponse
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.plugins.resolveUserPermissions
import lt.skautai.services.FirebaseNotificationService
import lt.skautai.services.NotificationRecipientService
import lt.skautai.services.PermissionContextService
import lt.skautai.services.RequisitionService
import java.util.UUID
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UserLeadershipRoles
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.requisitionRoutes(
    service: RequisitionService,
    firebaseNotificationService: FirebaseNotificationService,
    notificationRecipientService: NotificationRecipientService,
    apiPrefix: String = "/api"
) {
    authenticate("auth-jwt") {
        route("$apiPrefix/requisitions") {
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

                val permissionContext = PermissionContextService.resolve(userId, tuntasUUID)
                val updatedAfter = call.request.queryParameters["updatedAfter"]?.let(::parseInstantOrNull)
                if (
                    !permissionContext.has("requisitions.create") &&
                    !permissionContext.has("requisitions.approve") &&
                    !permissionContext.has("items.request.forward.bendras")
                ) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val permissions = permissionContext.permissions
                val isTopLevelReviewer = permissions.any {
                    it.permissionName == "requisitions.approve" && it.scope == "ALL"
                }
                val reviewableUnitIds = if (isTopLevelReviewer) emptyList() else resolveRequisitionReviewableUnitIds(userId, tuntasUUID)

                service.getAllRequests(tuntasUUID, userId, isTopLevelReviewer, reviewableUnitIds, updatedAfter)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "Failed to fetch requisitions")) }
            }

            get("{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try {
                    UUID.fromString(tuntasId)
                } catch (_: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val permissionContext = PermissionContextService.resolve(userId, tuntasUUID)
                if (
                    !permissionContext.has("requisitions.create") &&
                    !permissionContext.has("requisitions.approve") &&
                    !permissionContext.has("items.request.forward.bendras")
                ) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val requestId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request ID required"))
                val requestUUID = try {
                    UUID.fromString(requestId)
                } catch (_: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID"))
                }

                val permissions = permissionContext.permissions
                val isTopLevelReviewer = permissions.any {
                    it.permissionName == "requisitions.approve" && it.scope == "ALL"
                }
                val reviewableUnitIds = if (isTopLevelReviewer) emptyList() else resolveRequisitionReviewableUnitIds(userId, tuntasUUID)

                service.getRequest(requestUUID, tuntasUUID, userId, isTopLevelReviewer, reviewableUnitIds)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure {
                        val message = it.message ?: "Request not found"
                        val status = if (message.contains("accessible", ignoreCase = true)) {
                            HttpStatusCode.Forbidden
                        } else {
                            HttpStatusCode.NotFound
                        }
                        call.respond(status, ErrorResponse(message))
                    }
            }

            post {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try {
                    UUID.fromString(tuntasId)
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!PermissionContextService.resolve(userId, tuntasUUID).has("requisitions.create")) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val request = call.receiveValidated<CreateRequisitionRequest>()
                service.createRequest(tuntasUUID, userId, request)
                    .onSuccess {
                        firebaseNotificationService.sendRequisitionNextStepNotifications(
                            request = it,
                            recipients = notificationRecipientService,
                            excludeUserId = userId
                        )
                        call.respond(HttpStatusCode.Created, it)
                    }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create requisition")) }
            }

            delete("{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try {
                    UUID.fromString(tuntasId)
                } catch (_: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val requestId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request ID required"))
                val requestUUID = try {
                    UUID.fromString(requestId)
                } catch (_: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID"))
                }

                service.cancelRequest(requestUUID, tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Request cancelled")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to cancel requisition")) }
            }

            post("{id}/unit-review") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try {
                    UUID.fromString(tuntasId)
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val permissionContext = PermissionContextService.resolve(userId, tuntasUUID)
                if (
                    !permissionContext.has("items.request.approve.unit") &&
                    !permissionContext.has("items.request.forward.bendras")
                ) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val requestId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request ID required"))
                val requestUUID = try {
                    UUID.fromString(requestId)
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID"))
                }

                val request = call.receiveValidated<RequisitionUnitReviewRequest>()
                service.unitReview(requestUUID, tuntasUUID, userId, request)
                    .onSuccess {
                        firebaseNotificationService.sendRequisitionReviewNotification(it)
                        firebaseNotificationService.sendRequisitionNextStepNotifications(
                            request = it,
                            recipients = notificationRecipientService,
                            excludeUserId = userId
                        )
                        call.respond(HttpStatusCode.OK, it)
                    }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to process unit review")) }
            }

            post("{id}/top-level-review") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try {
                    UUID.fromString(tuntasId)
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("requisitions.approve", tuntasUUID)) return@post

                val requestId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request ID required"))
                val requestUUID = try {
                    UUID.fromString(requestId)
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID"))
                }

                val request = call.receiveValidated<RequisitionTopLevelReviewRequest>()
                service.topLevelReview(requestUUID, tuntasUUID, userId, request)
                    .onSuccess {
                        firebaseNotificationService.sendRequisitionReviewNotification(it)
                        call.respond(HttpStatusCode.OK, it)
                    }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to process top-level review")) }
            }

            post("{id}/mark-purchased") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try {
                    UUID.fromString(tuntasId)
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("requisitions.approve", tuntasUUID)) return@post

                val requestId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request ID required"))
                val requestUUID = try {
                    UUID.fromString(requestId)
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID"))
                }

                val request = call.receiveValidated<RequisitionMarkPurchasedRequest>()
                service.markPurchased(requestUUID, tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to mark requisition purchased")) }
            }

            post("{id}/add-to-inventory") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try {
                    UUID.fromString(tuntasId)
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("items.create", tuntasUUID)) return@post

                val requestId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request ID required"))
                val requestUUID = try {
                    UUID.fromString(requestId)
                } catch (_: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID"))
                }

                val request = call.receiveValidated<AddRequisitionToInventoryRequest>()
                service.addPurchasedItemsToInventory(requestUUID, tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to add requisition to inventory")) }
            }
        }
    }
}

private fun resolveRequisitionReviewableUnitIds(userId: UUID, tuntasId: UUID): List<UUID> {
    val unitLeaderRoles = listOf(
        "Draugininkas",
        "Draugininko pavaduotojas",
        "Gildijos pirmininkas",
        "Gildijos pirmininko pavaduotojas",
        "Vyr. skautu draugoves draugininkas",
        "Vyr. skautu draugoves draugininko pavaduotojas",
        "Vyr. skautu burelio pirmininkas",
        "Vyr. skautu burelio pirmininko pavaduotojas",
        "Vyr. skauciu draugoves draugininkas",
        "Vyr. skauciu draugoves draugininko pavaduotojas",
        "Vyr. skauciu burelio pirmininkas",
        "Vyr. skauciu burelio pirmininko pavaduotojas"
    )

    return transaction {
        UserLeadershipRoles
            .innerJoin(Roles)
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull() and
                    UserLeadershipRoles.organizationalUnitId.isNotNull() and
                    (Roles.name inList unitLeaderRoles)
            }
            .mapNotNull { it[UserLeadershipRoles.organizationalUnitId] }
            .distinct()
    }
}

private fun FirebaseNotificationService.sendRequisitionNextStepNotifications(
    request: RequisitionResponse,
    recipients: NotificationRecipientService,
    excludeUserId: UUID
) {
    val targetUsers = when {
        request.unitReviewStatus == "PENDING" -> {
            val unitId = request.requestingUnitId?.let(UUID::fromString) ?: return
            recipients.usersWithPermission(
                tuntasId = UUID.fromString(request.tuntasId),
                permissionName = "items.request.approve.unit",
                organizationalUnitId = unitId,
                excludeUserId = excludeUserId
            )
        }
        request.topLevelReviewStatus == "PENDING" -> {
            recipients.usersWithPermission(
                tuntasId = UUID.fromString(request.tuntasId),
                permissionName = "requisitions.approve",
                excludeUserId = excludeUserId
            )
        }
        else -> emptyList()
    }

    targetUsers.forEach { userId ->
        sendToUser(
            userId = userId,
            title = "Naujas pirkimo prasymas",
            body = "Pirkimo prasymas laukia jusu perziuros.",
            data = mapOf(
                "resource" to "requisitions",
                "requestId" to request.id,
                "tuntasId" to request.tuntasId,
                "status" to request.status
            )
        )
    }
}

private fun parseInstantOrNull(value: String): kotlinx.datetime.Instant? = try {
    kotlinx.datetime.Instant.parse(value)
} catch (_: Exception) {
    null
}

private fun FirebaseNotificationService.sendRequisitionReviewNotification(
    request: RequisitionResponse
) {
    if (request.status !in setOf("APPROVED", "REJECTED")) return

    val approved = request.status == "APPROVED"
    val title = if (approved) "Pirkimo prasymas patvirtintas" else "Pirkimo prasymas atmestas"
    val body = if (approved) {
        "Jusu pirkimo prasymas buvo patvirtintas."
    } else {
        "Jusu pirkimo prasymas buvo atmestas."
    }

    sendToUser(
        userId = UUID.fromString(request.createdByUserId),
        title = title,
        body = body,
        data = mapOf(
            "resource" to "requisitions",
            "requestId" to request.id,
            "tuntasId" to request.tuntasId,
            "status" to request.status
        )
    )
}
