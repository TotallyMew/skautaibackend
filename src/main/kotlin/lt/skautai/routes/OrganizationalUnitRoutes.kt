package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.models.requests.AssignUnitMemberRequest
import lt.skautai.models.requests.CreateOrganizationalUnitRequest
import lt.skautai.models.requests.UpdateOrganizationalUnitRequest
import lt.skautai.models.requests.UpdateUnitMemberVisibilityRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.services.OrganizationalUnitService
import lt.skautai.services.PermissionContextService
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

fun Route.organizationalUnitRoutes(service: OrganizationalUnitService, apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        route("$apiPrefix/organizational-units") {

            get {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }
                val principal = call.principal<JWTPrincipal>()!!
                val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))
                if (!isActiveTuntasMember(callerUserId, tuntasUUID)) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))
                }
                val permissionContext = PermissionContextService.resolve(callerUserId, tuntasUUID)
                if (!permissionContext.has("organizational_units.view")) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val type = call.request.queryParameters["type"]
                val visibleUnitIds = if (permissionContext.hasAll("organizational_units.view")) {
                    null
                } else {
                    permissionContext.scopedUnitIds("organizational_units.view")
                }
                service.getUnits(tuntasUUID, type, visibleUnitIds = visibleUnitIds)
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
                val permissionContext = PermissionContextService.resolve(callerUserId, tuntasUUID)
                if (!permissionContext.targetAllowed("organizational_units.view", unitUUID)) {
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

                val request = call.receiveValidated<CreateOrganizationalUnitRequest>()

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

                val request = call.receiveValidated<UpdateOrganizationalUnitRequest>()

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
                    val principal = call.principal<JWTPrincipal>()!!
                    val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))
                    if (!isActiveTuntasMember(callerUserId, tuntasUUID)) {
                        return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))
                    }

                    if (!checkPermission("members.view", tuntasUUID, unitUUID)) return@get

                    service.getUnitMembers(unitUUID, tuntasUUID, callerUserId)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to fetch members")) }
                }

                put("{userId}/visibility") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasUUID = call.request.headers["X-Tuntas-Id"]
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Valid X-Tuntas-Id header required"))
                    val unitUUID = call.parameters["id"]
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID"))
                    val targetUserId = call.parameters["userId"]
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                    val request = call.receiveValidated<UpdateUnitMemberVisibilityRequest>()

                    service.updateUnitMemberVisibility(
                        unitId = unitUUID,
                        targetUserId = targetUserId,
                        tuntasId = tuntasUUID,
                        callerUserId = callerUserId,
                        request = request
                    )
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { call.respond(HttpStatusCode.Forbidden, ErrorResponse(it.message ?: "Failed to update visibility")) }
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

                    val request = call.receiveValidated<AssignUnitMemberRequest>()

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

            get("{id}/privacy-audit") {
                val principal = call.principal<JWTPrincipal>()!!
                val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasUUID = call.request.headers["X-Tuntas-Id"]
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Valid X-Tuntas-Id header required"))
                val unitUUID = call.parameters["id"]
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID"))

                service.getSeniorUnitAccessAudit(unitUUID, tuntasUUID, callerUserId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.Forbidden, ErrorResponse(it.message ?: "Failed to fetch privacy audit")) }
            }
        }
    }
}

private fun isActiveTuntasMember(userId: UUID, tuntasId: UUID): Boolean = transaction {
    UserTuntasMemberships.selectAll()
        .where {
            (UserTuntasMemberships.userId eq userId) and
                (UserTuntasMemberships.tuntasId eq tuntasId) and
                (UserTuntasMemberships.leftAt.isNull())
        }
        .firstOrNull() != null
}
