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
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.plugins.resolveUserPermissions
import lt.skautai.services.BendrasInventoryRequestService
import java.util.*
import lt.skautai.database.tables.BendrasInventoryRequests
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UserLeadershipRoles
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.bendrasInventoryRequestRoutes(service: BendrasInventoryRequestService) {
    authenticate("auth-jwt") {
        route("/api/inventory-requests") {

            get {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("items.view", tuntasUUID)) return@get

                val userPerms = resolveUserPermissions(userId, tuntasUUID)
                val isAdmin = userPerms.any {
                    it.permissionName == "items.request.approve.bendras" && it.scope == "ALL"
                }
                val unitIds = if (isAdmin) {
                    emptyList()
                } else {
                    resolveReviewableUnitIds(userId, tuntasUUID)
                }

                service.getAllRequests(tuntasUUID, userId, isAdmin, unitIds)
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

                if (!checkPermission("items.view", tuntasUUID)) return@get

                val requestId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request ID required"))
                val requestUUID = try { UUID.fromString(requestId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request ID"))
                }

                val userPerms = resolveUserPermissions(userId, tuntasUUID)
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

                val request = call.receive<CreateBendrasInventoryRequestRequest>()

                service.createRequest(tuntasUUID, requestedByUserId, request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
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
                    .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Request cancelled")) }
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

                val request = call.receive<DraugininkasReviewRequest>()

                service.draugininkasReview(requestUUID, tuntasUUID, reviewerUserId, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
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

                val request = call.receive<TopLevelReviewRequest>()

                service.topLevelReview(requestUUID, tuntasUUID, reviewerUserId, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
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
