package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.AssignUnitMemberRequest
import lt.skautai.models.requests.CreateOrganizationalUnitRequest
import lt.skautai.models.requests.UpdateOrganizationalUnitRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.plugins.resolveUserPermissions
import lt.skautai.services.OrganizationalUnitService
import java.util.*

fun Route.organizationalUnitRoutes(service: OrganizationalUnitService) {
    authenticate("auth-jwt") {
        route("/api/organizational-units") {

            get {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val principal = call.principal<JWTPrincipal>()!!
                val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))
                val resolvedPermissions = resolveUserPermissions(callerUserId, tuntasUUID)
                val canViewAll = resolvedPermissions.any {
                    (it.permissionName == "members.view" || it.permissionName == "organizational_units.manage") && it.scope == "ALL"
                }
                val visibleUnitIds = if (canViewAll) {
                    null
                } else {
                    resolvedPermissions
                        .filter { it.permissionName == "members.view" }
                        .flatMap { it.userOrgUnitIds }
                        .toSet()
                }
                if (!canViewAll && visibleUnitIds.orEmpty().isEmpty()) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val type = call.request.queryParameters["type"]

                service.getUnits(tuntasUUID, type, visibleUnitIds)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "Failed to fetch units")) }
            }

            get("{id}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val unitId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unit ID required"))
                val unitUUID = try { UUID.fromString(unitId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID"))
                }

                val principal = call.principal<JWTPrincipal>()!!
                val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))
                val resolvedPermissions = resolveUserPermissions(callerUserId, tuntasUUID)
                val canViewAll = resolvedPermissions.any {
                    (it.permissionName == "members.view" || it.permissionName == "organizational_units.manage") && it.scope == "ALL"
                }
                val visibleUnitIds = resolvedPermissions
                    .filter { it.permissionName == "members.view" }
                    .flatMap { it.userOrgUnitIds }
                    .toSet()
                if (!canViewAll && unitUUID !in visibleUnitIds) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                service.getUnit(unitUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Unit not found")) }
            }

            post {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("organizational_units.manage", tuntasUUID)) return@post

                val request = call.receive<CreateOrganizationalUnitRequest>()

                service.createUnit(tuntasUUID, request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create unit")) }
            }

            put("{id}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("organizational_units.manage", tuntasUUID)) return@put

                val unitId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unit ID required"))
                val unitUUID = try { UUID.fromString(unitId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID"))
                }

                val request = call.receive<UpdateOrganizationalUnitRequest>()

                service.updateUnit(unitUUID, tuntasUUID, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to update unit")) }
            }

            delete("{id}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("organizational_units.manage", tuntasUUID)) return@delete

                val unitId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unit ID required"))
                val unitUUID = try { UUID.fromString(unitId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID ID"))
                }

                service.deleteUnit(unitUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Unit deleted")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to delete unit")) }
            }
            route("{id}/members") {

                get {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    val unitId = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unit ID required"))
                    val unitUUID = try { UUID.fromString(unitId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID"))
                    }

                    if (!checkPermission("members.view", tuntasUUID, unitUUID)) return@get

                    service.getUnitMembers(unitUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to fetch members")) }
                }

                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val assignedByUserId = UUID.fromString(principal.getClaim("userId", String::class))

                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    val unitId = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unit ID required"))
                    val unitUUID = try { UUID.fromString(unitId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID"))
                    }

                    if (!checkPermission("unit.members.manage", tuntasUUID, unitUUID)) return@post

                    val request = call.receive<AssignUnitMemberRequest>()

                    service.assignUnitMember(unitUUID, tuntasUUID, assignedByUserId, request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to assign member")) }
                }

                post("{userId}/move") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val assignedByUserId = UUID.fromString(principal.getClaim("userId", String::class))

                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    val unitId = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unit ID required"))
                    val unitUUID = try { UUID.fromString(unitId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID"))
                    }

                    if (!checkPermission("unit.members.manage", tuntasUUID, unitUUID)) return@post

                    val userId = call.parameters["userId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required"))
                    val userUUID = try { UUID.fromString(userId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                    }

                    service.moveUnitMember(unitUUID, tuntasUUID, userUUID, assignedByUserId)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to move member")) }
                }

                post("me/leave") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))

                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    val unitId = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unit ID required"))
                    val unitUUID = try { UUID.fromString(unitId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID"))
                    }

                    service.leaveUnit(unitUUID, tuntasUUID, callerUserId)
                        .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Left unit")) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to leave unit")) }
                }

                delete("{userId}") {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    val unitId = call.parameters["id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unit ID required"))
                    val unitUUID = try { UUID.fromString(unitId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID"))
                    }

                    if (!checkPermission("unit.members.manage", tuntasUUID, unitUUID)) return@delete

                    val userId = call.parameters["userId"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required"))
                    val userUUID = try { UUID.fromString(userId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                    }

                    service.removeUnitMember(unitUUID, tuntasUUID, userUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Member removed from draugove")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to remove member")) }
                }
            }
        }
    }
}
