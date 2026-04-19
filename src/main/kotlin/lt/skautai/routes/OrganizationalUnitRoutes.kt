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
import lt.skautai.plugins.checkPermission
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

                if (!checkPermission("members.view", tuntasUUID)) return@get

                val type = call.request.queryParameters["type"]

                service.getUnits(tuntasUUID, type)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "Failed to fetch units")) }
            }

            get("{id}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("members.view", tuntasUUID)) return@get

                val unitId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unit ID required"))
                val unitUUID = try { UUID.fromString(unitId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID"))
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
                    .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Unit deleted")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to delete unit")) }
            }
            route("{id}/members") {

                get {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    if (!checkPermission("members.view", tuntasUUID)) return@get

                    val unitId = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unit ID required"))
                    val unitUUID = try { UUID.fromString(unitId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID"))
                    }

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

                    if (!checkPermission("unit.members.manage", tuntasUUID)) return@post

                    val unitId = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unit ID required"))
                    val unitUUID = try { UUID.fromString(unitId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID"))
                    }

                    val request = call.receive<AssignUnitMemberRequest>()

                    service.assignUnitMember(unitUUID, tuntasUUID, assignedByUserId, request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to assign member")) }
                }

                delete("{userId}") {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    if (!checkPermission("unit.members.manage", tuntasUUID)) return@delete

                    val unitId = call.parameters["id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unit ID required"))
                    val unitUUID = try { UUID.fromString(unitId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid unit ID"))
                    }

                    val userId = call.parameters["userId"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required"))
                    val userUUID = try { UUID.fromString(userId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                    }

                    service.removeUnitMember(unitUUID, tuntasUUID, userUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Member removed from draugove")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to remove member")) }
                }
            }
        }
    }
}