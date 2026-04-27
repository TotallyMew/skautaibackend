package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.Tuntai
import lt.skautai.models.requests.AssignLeadershipRoleRequest
import lt.skautai.models.requests.AssignRankRequest
import lt.skautai.models.requests.UpdateLeadershipRoleRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.models.responses.RoleListResponse
import lt.skautai.models.responses.RoleResponse
import lt.skautai.services.MemberService
import lt.skautai.services.OrganizationalUnitService
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlinx.datetime.Clock
import java.util.*

private val supportedRankNames = setOf(
    "Skautas",
    "Patyres skautas",
    "Vyr. skautas kandidatas",
    "Vyr. skautas",
    "Vadovas"
)

fun Route.superAdminRoutes(
    memberService: MemberService,
    organizationalUnitService: OrganizationalUnitService
) {
    authenticate("auth-super-admin") {
        route("/api/super-admin/tuntai") {
            get {
                val tuntai = transaction {
                    Tuntai.selectAll().map {
                        mapOf(
                            "id" to it[Tuntai.id].toString(),
                            "name" to it[Tuntai.name],
                            "krastas" to (it[Tuntai.krastas] ?: ""),
                            "status" to it[Tuntai.status],
                            "contactEmail" to (it[Tuntai.contactEmail] ?: "")
                        )
                    }
                }
                call.respond(HttpStatusCode.OK, tuntai)
            }

            post("/{id}/approve") {
                val tuntasId = try {
                    UUID.fromString(call.parameters["id"])
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val principal = call.principal<JWTPrincipal>()!!
                val adminId = UUID.fromString(principal.getClaim("userId", String::class))

                val result = transaction {
                    val tuntas = Tuntai.selectAll()
                        .where { Tuntai.id eq tuntasId }
                        .firstOrNull()
                        ?: return@transaction Result.failure(Exception("Tuntas not found"))

                    if (tuntas[Tuntai.status] != "PENDING") {
                        return@transaction Result.failure(Exception("Tuntas is not pending approval"))
                    }

                    Tuntai.update({ Tuntai.id eq tuntasId }) {
                        it[status] = "ACTIVE"
                        it[approvedBySuperAdminId] = adminId
                        it[approvedAt] = Clock.System.now()
                    }

                    Result.success(MessageResponse("Tuntas approved successfully"))
                }

                result
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Approval failed")) }
            }

            post("/{id}/reject") {
                val tuntasId = try {
                    UUID.fromString(call.parameters["id"])
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val result = transaction {
                    val tuntas = Tuntai.selectAll()
                        .where { Tuntai.id eq tuntasId }
                        .firstOrNull()
                        ?: return@transaction Result.failure(Exception("Tuntas not found"))

                    if (tuntas[Tuntai.status] != "PENDING") {
                        return@transaction Result.failure(Exception("Tuntas is not pending approval"))
                    }

                    Tuntai.update({ Tuntai.id eq tuntasId }) {
                        it[status] = "REJECTED"
                    }

                    Result.success(MessageResponse("Tuntas rejected"))
                }

                result
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Rejection failed")) }
            }

            route("/{id}") {
                get("/roles") {
                    val tuntasId = parseTuntasId() ?: return@get
                    val roles = transaction {
                        Roles.selectAll()
                            .where { Roles.tuntasId eq tuntasId }
                            .filter {
                                it[Roles.roleType] != "RANK" || it[Roles.name] in supportedRankNames
                            }
                            .map {
                                RoleResponse(
                                    id = it[Roles.id].toString(),
                                    name = it[Roles.name],
                                    roleType = it[Roles.roleType],
                                    isSystemRole = it[Roles.isSystemRole]
                                )
                            }
                    }
                    call.respond(HttpStatusCode.OK, RoleListResponse(roles = roles, total = roles.size))
                }

                get("/organizational-units") {
                    val tuntasId = parseTuntasId() ?: return@get
                    organizationalUnitService.getUnits(tuntasId)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(it.message ?: "Failed to fetch organizational units")
                            )
                        }
                }

                get("/members") {
                    val tuntasId = parseTuntasId() ?: return@get
                    memberService.getMembers(tuntasId)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(it.message ?: "Failed to fetch members")
                            )
                        }
                }

                get("/members/{userId}") {
                    val tuntasId = parseTuntasId() ?: return@get
                    val userId = parseUserId() ?: return@get
                    memberService.getMember(userId, tuntasId)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure {
                            call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse(it.message ?: "Member not found")
                            )
                        }
                }

                post("/members/{userId}/leadership-roles") {
                    val tuntasId = parseTuntasId() ?: return@post
                    val userId = parseUserId() ?: return@post
                    val request = call.receive<AssignLeadershipRoleRequest>()

                    memberService.assignLeadershipRole(
                        targetUserId = userId,
                        tuntasId = tuntasId,
                        assignedByUserId = null,
                        request = request
                    ).onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(it.message ?: "Failed to assign leadership role")
                            )
                        }
                }

                put("/members/{userId}/leadership-roles/{assignmentId}") {
                    val tuntasId = parseTuntasId() ?: return@put
                    val userId = parseUserId() ?: return@put
                    val assignmentId = parseAssignmentId() ?: return@put
                    val request = call.receive<UpdateLeadershipRoleRequest>()

                    memberService.superAdminUpdateLeadershipRole(
                        targetUserId = userId,
                        assignmentId = assignmentId,
                        tuntasId = tuntasId,
                        request = request
                    ).onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(it.message ?: "Failed to update leadership role")
                            )
                        }
                }

                delete("/members/{userId}/leadership-roles/{assignmentId}") {
                    val tuntasId = parseTuntasId() ?: return@delete
                    val userId = parseUserId() ?: return@delete
                    val assignmentId = parseAssignmentId() ?: return@delete

                    memberService.superAdminRemoveLeadershipRole(
                        targetUserId = userId,
                        assignmentId = assignmentId,
                        tuntasId = tuntasId
                    ).onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Leadership role removed")) }
                        .onFailure {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(it.message ?: "Failed to remove leadership role")
                            )
                        }
                }

                post("/members/{userId}/ranks") {
                    val tuntasId = parseTuntasId() ?: return@post
                    val userId = parseUserId() ?: return@post
                    val request = call.receive<AssignRankRequest>()

                    memberService.assignRank(
                        targetUserId = userId,
                        tuntasId = tuntasId,
                        assignedByUserId = null,
                        request = request
                    ).onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(it.message ?: "Failed to assign rank")
                            )
                        }
                }

                delete("/members/{userId}/ranks/{rankId}") {
                    val tuntasId = parseTuntasId() ?: return@delete
                    val userId = parseUserId() ?: return@delete
                    val rankId = parseRankId() ?: return@delete

                    memberService.removeRank(
                        targetUserId = userId,
                        rankId = rankId,
                        tuntasId = tuntasId
                    ).onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Rank removed")) }
                        .onFailure {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(it.message ?: "Failed to remove rank")
                            )
                        }
                }
            }
        }
    }
}

private suspend fun RoutingContext.parseTuntasId(): UUID? {
    val raw = call.parameters["id"]
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Tuntas ID required")).let { null }
    return try {
        UUID.fromString(raw)
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
        null
    }
}

private suspend fun RoutingContext.parseUserId(): UUID? {
    val raw = call.parameters["userId"]
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("User ID required")).let { null }
    return try {
        UUID.fromString(raw)
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
        null
    }
}

private suspend fun RoutingContext.parseAssignmentId(): UUID? {
    val raw = call.parameters["assignmentId"]
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Assignment ID required")).let { null }
    return try {
        UUID.fromString(raw)
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid assignment ID"))
        null
    }
}

private suspend fun RoutingContext.parseRankId(): UUID? {
    val raw = call.parameters["rankId"]
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Rank ID required")).let { null }
    return try {
        UUID.fromString(raw)
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid rank ID"))
        null
    }
}
