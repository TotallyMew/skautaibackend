package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.CreateBendrasInventoryRequestRequest
import lt.skautai.models.requests.DraugininkasReviewRequest
import lt.skautai.models.requests.TopLevelReviewRequest
import lt.skautai.models.responses.BendrasInventoryRequestResponse
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.plugins.resolveUserPermissions
import lt.skautai.services.BendrasInventoryRequestService
import lt.skautai.services.FirebaseNotificationService
import lt.skautai.services.NotificationRecipientService
import lt.skautai.services.PermissionContextService
import java.util.*
import lt.skautai.database.tables.BendrasInventoryRequests
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UserLeadershipRoles
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.bendrasInventoryRequestRoutes(
    service: BendrasInventoryRequestService,
    firebaseNotificationService: FirebaseNotificationService,
    notificationRecipientService: NotificationRecipientService,
    apiPrefix: String = "/api"
) {
    authenticate("auth-jwt") {
        route("$apiPrefix/inventory-requests") {

            get {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val updatedAfter = call.request.queryParameters["updatedAfter"]?.let(::parseInstantOrNull)
                val userPerms = PermissionContextService.resolve(userId, tuntasUUID).permissions
                if (userPerms.none { it.permissionName == "items.view" }) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }
                val isAdmin = userPerms.any {
                    it.permissionName == "items.request.approve.bendras" && it.scope == "ALL"
                }
                val unitIds = if (isAdmin) {
                    emptyList()
                } else {
                    resolveReviewableUnitIds(userId, tuntasUUID)
                }

                service.getAllRequests(tuntasUUID, userId, isAdmin, unitIds, updatedAfter)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "Failed to fetch requests")) }
            }

            get("{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val requestId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request ID required"))
                val requestUUID = try { UUID.fromString(requestId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID"))
                }

                val userPerms = resolveUserPermissions(userId, tuntasUUID)
                if (userPerms.none { it.permissionName == "items.view" }) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }
                val isAdmin = userPerms.any {
                    it.permissionName == "items.request.approve.bendras" && it.scope == "ALL"
                }
                val unitIds = if (isAdmin) {
                    emptyList()
                } else {
                    resolveReviewableUnitIds(userId, tuntasUUID)
                }

                service.getRequest(requestUUID, tuntasUUID, userId, isAdmin, unitIds)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure {
                        val message = it.message ?: "Request not found"
                        val status = when {
                            message.contains("not accessible", ignoreCase = true) -> HttpStatusCode.Forbidden
                            message.contains("not found", ignoreCase = true) -> HttpStatusCode.NotFound
                            else -> HttpStatusCode.BadRequest
                        }
                        call.respond(status, ErrorResponse(message))
                    }
            }

            post {
                val principal = call.principal<JWTPrincipal>()!!
                val requestedByUserId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("items.request.bendras", tuntasUUID)) return@post

                val request = call.receiveValidated<CreateBendrasInventoryRequestRequest>()

                service.createRequest(tuntasUUID, requestedByUserId, request)
                    .onSuccess {
                        firebaseNotificationService.sendBendrasRequestNextStepNotifications(
                            request = it,
                            recipients = notificationRecipientService,
                            excludeUserId = requestedByUserId
                        )
                        call.respond(HttpStatusCode.Created, it)
                    }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create request")) }
            }

            delete("{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val requestingUserId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val requestId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request ID required"))
                val requestUUID = try { UUID.fromString(requestId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID"))
                }

                service.cancelRequest(requestUUID, tuntasUUID, requestingUserId)
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Request cancelled")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to cancel request")) }
            }

            post("{id}/draugininkas-review") {
                val principal = call.principal<JWTPrincipal>()!!
                val reviewerUserId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val requestId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request ID required"))
                val requestUUID = try { UUID.fromString(requestId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID"))
                }

                // Look up draugove from the request before permission check
                val requestingUnitUUID = transaction {
                    BendrasInventoryRequests.selectAll()
                        .where {
                            (BendrasInventoryRequests.id eq requestUUID) and
                                    (BendrasInventoryRequests.tuntasId eq tuntasUUID)
                        }
                        .firstOrNull()
                        ?.get(BendrasInventoryRequests.requestingUnitId)
                }

                if (!checkPermission("items.request.forward.bendras", tuntasUUID, requestingUnitUUID)) return@post

                val request = call.receiveValidated<DraugininkasReviewRequest>()

                service.draugininkasReview(requestUUID, tuntasUUID, reviewerUserId, request)
                    .onSuccess {
                        firebaseNotificationService.sendBendrasRequestReviewNotification(it)
                        firebaseNotificationService.sendBendrasRequestNextStepNotifications(
                            request = it,
                            recipients = notificationRecipientService,
                            excludeUserId = reviewerUserId
                        )
                        call.respond(HttpStatusCode.OK, it)
                    }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to process review")) }
            }

            post("{id}/top-level-review") {
                val principal = call.principal<JWTPrincipal>()!!
                val reviewerUserId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("items.request.approve.bendras", tuntasUUID)) return@post

                val requestId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request ID required"))
                val requestUUID = try { UUID.fromString(requestId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID"))
                }

                val request = call.receiveValidated<TopLevelReviewRequest>()

                service.topLevelReview(requestUUID, tuntasUUID, reviewerUserId, request)
                    .onSuccess {
                        firebaseNotificationService.sendBendrasRequestReviewNotification(it)
                        call.respond(HttpStatusCode.OK, it)
                    }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to process review")) }
            }
        }
    }
}

private fun resolveReviewableUnitIds(userId: UUID, tuntasId: UUID): List<UUID> {
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

private fun parseInstantOrNull(value: String): kotlinx.datetime.Instant? = try {
    kotlinx.datetime.Instant.parse(value)
} catch (_: Exception) {
    null
}

private fun FirebaseNotificationService.sendBendrasRequestNextStepNotifications(
    request: BendrasInventoryRequestResponse,
    recipients: NotificationRecipientService,
    excludeUserId: UUID
) {
    val targetUsers = when {
        request.draugininkasStatus == "PENDING" -> {
            val unitId = request.requestingUnitId?.let(UUID::fromString) ?: return
            recipients.usersWithPermission(
                tuntasId = UUID.fromString(request.tuntasId),
                permissionName = "items.request.forward.bendras",
                organizationalUnitId = unitId,
                excludeUserId = excludeUserId
            )
        }
        request.topLevelStatus == "PENDING" &&
            (!request.needsDraugininkasApproval || request.draugininkasStatus == "FORWARDED") -> {
            recipients.usersWithPermission(
                tuntasId = UUID.fromString(request.tuntasId),
                permissionName = "items.request.approve.bendras",
                excludeUserId = excludeUserId
            )
        }
        else -> emptyList()
    }

    targetUsers.forEach { userId ->
        sendToUser(
            userId = userId,
            title = "Naujas paemimo prasymas",
            body = "Prasymas \"${request.itemName}\" laukia perziuros.",
            data = mapOf(
                "resource" to "bendras_requests",
                "requestId" to request.id,
                "tuntasId" to request.tuntasId,
                "status" to request.topLevelStatus
            )
        )
    }
}

private fun FirebaseNotificationService.sendBendrasRequestReviewNotification(
    request: BendrasInventoryRequestResponse
) {
    val rejectedByUnit = request.draugininkasStatus == "REJECTED"
    val finishedTopLevel = request.topLevelStatus in setOf("APPROVED", "REJECTED")
    if (!rejectedByUnit && !finishedTopLevel) return

    val approved = request.topLevelStatus == "APPROVED"
    val title = if (approved) "Paemimo prasymas patvirtintas" else "Paemimo prasymas atmestas"
    val body = if (approved) {
        "Jusu prasymas \"${request.itemName}\" buvo patvirtintas."
    } else {
        "Jusu prasymas \"${request.itemName}\" buvo atmestas."
    }

    sendToUser(
        userId = UUID.fromString(request.requestedByUserId),
        title = title,
        body = body,
        data = mapOf(
            "resource" to "bendras_requests",
            "requestId" to request.id,
            "tuntasId" to request.tuntasId,
            "status" to request.topLevelStatus
        )
    )
}
