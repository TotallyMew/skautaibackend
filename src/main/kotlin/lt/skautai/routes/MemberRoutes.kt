package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.AssignLeadershipRoleRequest
import lt.skautai.models.requests.AssignRankRequest
import lt.skautai.models.requests.TransferTuntininkasRequest
import lt.skautai.models.requests.UpdateLeadershipRoleRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.services.MemberService
import java.util.*

fun Route.memberRoutes(memberService: MemberService, apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        route("$apiPrefix/members") {

            get {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val principal = call.principal<JWTPrincipal>()!!
                val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))
                if (!memberService.canAccessMemberDirectory(callerUserId, tuntasUUID)) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }
                memberService.getMembers(tuntasUUID, callerUserId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure {
                        val status = if (it.message?.contains("active member", ignoreCase = true) == true) {
                            HttpStatusCode.Forbidden
                        } else {
                            HttpStatusCode.InternalServerError
                        }
                        call.respond(status, ErrorResponse(it.message ?: "Failed to fetch members"))
                    }
            }

            get("{userId}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val principal = call.principal<JWTPrincipal>()!!
                val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))
                if (!memberService.canAccessMemberDirectory(callerUserId, tuntasUUID)) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val userId = call.parameters["userId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required"))
                val userUUID = try { UUID.fromString(userId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                }

                memberService.getMember(userUUID, tuntasUUID, callerUserId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure {
                        val status = if (it.message?.contains("active member", ignoreCase = true) == true) {
                            HttpStatusCode.Forbidden
                        } else {
                            HttpStatusCode.NotFound
                        }
                        call.respond(status, ErrorResponse(it.message ?: "Member not found"))
                    }
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

                    val request = call.receiveValidated<AssignLeadershipRoleRequest>()

                    memberService.assignLeadershipRole(userUUID, tuntasUUID, assignedByUserId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to assign role")) }
                }

                put("{assignmentId}") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))

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

                    val request = call.receiveValidated<UpdateLeadershipRoleRequest>()

                    memberService.updateLeadershipRole(userUUID, assignmentUUID, tuntasUUID, callerUserId, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to update role")) }
                }

                delete("{assignmentId}") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))

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

                    memberService.removeLeadershipRole(userUUID, assignmentUUID, tuntasUUID, callerUserId)
                        .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Leadership role removed")) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to remove role")) }
                }
            }

            post("me/leadership-roles/{assignmentId}/step-down") {
                val principal = call.principal<JWTPrincipal>()!!
                val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val assignmentId = call.parameters["assignmentId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Assignment ID required"))
                val assignmentUUID = try { UUID.fromString(assignmentId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid assignment ID"))
                }

                memberService.stepDownLeadershipRole(callerUserId, assignmentUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Leadership role resigned")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to step down")) }
            }

            post("me/tuntininkas/transfer") {
                val principal = call.principal<JWTPrincipal>()!!
                val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val request = call.receiveValidated<TransferTuntininkasRequest>()
                val successorUserId = try { UUID.fromString(request.successorUserId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
                }

                memberService.transferTuntininkas(callerUserId, tuntasUUID, successorUserId)
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Tuntininkas role transferred")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to transfer tuntininkas role")) }
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

                    val request = call.receiveValidated<AssignRankRequest>()

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
                        .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Rank removed")) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to remove rank")) }
                }
            }
            delete("{userId}/remove") {
                val principal = call.principal<JWTPrincipal>()!!
                val callerUserId = UUID.fromString(principal.getClaim("userId", String::class))

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

                memberService.removeMember(userUUID, tuntasUUID, callerUserId)
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Member removed")) }
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
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Successfully resigned from tuntas")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to resign")) }
            }
        }
    }
}
