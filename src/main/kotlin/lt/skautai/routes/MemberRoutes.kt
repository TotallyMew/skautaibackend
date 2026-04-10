package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.AssignLeadershipRoleRequest
import lt.skautai.models.requests.AssignRankRequest
import lt.skautai.models.requests.UpdateLeadershipRoleRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.services.MemberService
import java.util.*

fun Route.memberRoutes(memberService: MemberService) {
    authenticate("auth-jwt") {
        route("/api/members") {

            get {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("members.view", tuntasUUID)) return@get

                memberService.getMembers(tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "Failed to fetch members")) }
            }

            get("{userId}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("members.view", tuntasUUID)) return@get

                val userId = call.parameters["userId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required"))
                val userUUID = try { UUID.fromString(userId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                }

                memberService.getMember(userUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Member not found")) }
            }

            route("{userId}/leadership-roles") {

                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val assignedByUserId = UUID.fromString(principal.getClaim("userId", String::class))

                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    if (!checkPermission("roles.assign", tuntasUUID)) return@post

                    val userId = call.parameters["userId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required"))
                    val userUUID = try { UUID.fromString(userId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                    }

                    val request = call.receive<AssignLeadershipRoleRequest>()

                    memberService.assignLeadershipRole(userUUID, tuntasUUID, assignedByUserId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to assign role")) }
                }

                put("{assignmentId}") {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    if (!checkPermission("roles.assign", tuntasUUID)) return@put

                    val userId = call.parameters["userId"]
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required"))
                    val userUUID = try { UUID.fromString(userId) } catch (e: Exception) {
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                    }

                    val assignmentId = call.parameters["assignmentId"]
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Assignment ID required"))
                    val assignmentUUID = try { UUID.fromString(assignmentId) } catch (e: Exception) {
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid assignment ID"))
                    }

                    val request = call.receive<UpdateLeadershipRoleRequest>()

                    memberService.updateLeadershipRole(userUUID, assignmentUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to update role")) }
                }

                delete("{assignmentId}") {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    if (!checkPermission("roles.assign", tuntasUUID)) return@delete

                    val userId = call.parameters["userId"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required"))
                    val userUUID = try { UUID.fromString(userId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                    }

                    val assignmentId = call.parameters["assignmentId"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Assignment ID required"))
                    val assignmentUUID = try { UUID.fromString(assignmentId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid assignment ID"))
                    }

                    memberService.removeLeadershipRole(userUUID, assignmentUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Leadership role removed")) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to remove role")) }
                }
            }

            route("{userId}/ranks") {

                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val assignedByUserId = UUID.fromString(principal.getClaim("userId", String::class))

                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    if (!checkPermission("roles.assign", tuntasUUID)) return@post

                    val userId = call.parameters["userId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required"))
                    val userUUID = try { UUID.fromString(userId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                    }

                    val request = call.receive<AssignRankRequest>()

                    memberService.assignRank(userUUID, tuntasUUID, assignedByUserId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to assign rank")) }
                }

                delete("{rankId}") {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    if (!checkPermission("roles.assign", tuntasUUID)) return@delete

                    val userId = call.parameters["userId"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required"))
                    val userUUID = try { UUID.fromString(userId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                    }

                    val rankId = call.parameters["rankId"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Rank ID required"))
                    val rankUUID = try { UUID.fromString(rankId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid rank ID"))
                    }

                    memberService.removeRank(userUUID, rankUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Rank removed")) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to remove rank")) }
                }
            }
            delete("{userId}/remove") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("members.remove", tuntasUUID)) return@delete

                val userId = call.parameters["userId"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required"))
                val userUUID = try { UUID.fromString(userId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                }

                memberService.removeMember(userUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Member removed")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to remove member")) }
            }

            post("{userId}/resign") {
                val principal = call.principal<JWTPrincipal>()!!
                val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                memberService.resignMember(callerUserId, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Successfully resigned from tuntas")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to resign")) }
            }
        }
    }
}