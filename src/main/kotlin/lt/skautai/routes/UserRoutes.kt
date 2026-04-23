package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.database.tables.Tuntai
import lt.skautai.database.tables.UnitAssignments
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.UserRanks
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.plugins.resolveUserPermissions
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import org.jetbrains.exposed.sql.deleteWhere

fun Route.userRoutes() {
    authenticate("auth-jwt") {
        route("/api/users/me") {
            get("/permissions") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasIdHeader = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing X-Tuntas-Id header"))
                val tuntasId = try { UUID.fromString(tuntasIdHeader) }
                    catch (e: Exception) { return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID")) }

                val isMember = transaction {
                    UserTuntasMemberships.selectAll().where {
                        (UserTuntasMemberships.userId eq userId) and
                        (UserTuntasMemberships.tuntasId eq tuntasId) and
                        (UserTuntasMemberships.leftAt.isNull())
                    }.firstOrNull() != null
                }
                if (!isMember) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))

                val resolvedPermissions = resolveUserPermissions(userId, tuntasId)
                val perms = (
                    resolvedPermissions.map { it.permissionName } +
                        resolvedPermissions.map { "${it.permissionName}:${it.scope}" }
                    ).distinct()

                call.respond(HttpStatusCode.OK, mapOf("permissions" to perms))
            }

            get("/tuntai") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntai = transaction {
                    UserTuntasMemberships
                        .innerJoin(Tuntai, { UserTuntasMemberships.tuntasId }, { Tuntai.id })
                        .selectAll()
                        .where {
                            (UserTuntasMemberships.userId eq userId) and
                                    (UserTuntasMemberships.leftAt.isNull()) and
                                    (Tuntai.status inList listOf("ACTIVE", "PENDING", "REJECTED"))
                        }
                        .map {
                            mapOf(
                                "id" to it[Tuntai.id].toString(),
                                "name" to it[Tuntai.name],
                                "krastas" to (it[Tuntai.krastas] ?: ""),
                                "contactEmail" to (it[Tuntai.contactEmail] ?: ""),
                                "status" to it[Tuntai.status]
                            )
                        }
                }

                call.respond(HttpStatusCode.OK, tuntai)
            }

            post("/tuntai/{tuntasId}/leave") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.parameters["tuntasId"]
                    ?.let {
                        try {
                            UUID.fromString(it)
                        } catch (e: Exception) {
                            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                        }
                    }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing tuntas ID"))

                val result = transaction {
                    val membership = UserTuntasMemberships.selectAll()
                        .where {
                            (UserTuntasMemberships.userId eq userId) and
                                (UserTuntasMemberships.tuntasId eq tuntasId) and
                                UserTuntasMemberships.leftAt.isNull()
                        }
                        .firstOrNull()
                        ?: return@transaction Result.failure(Exception("Not a member of this tuntas"))

                    val now = Clock.System.now()
                    UserTuntasMemberships.update({ UserTuntasMemberships.id eq membership[UserTuntasMemberships.id] }) {
                        it[leftAt] = now
                    }
                    UserLeadershipRoles.update({
                        (UserLeadershipRoles.userId eq userId) and
                            (UserLeadershipRoles.tuntasId eq tuntasId) and
                            UserLeadershipRoles.leftAt.isNull()
                    }) {
                        it[leftAt] = now
                        it[termStatus] = "LEFT"
                    }
                    UnitAssignments.update({
                        (UnitAssignments.userId eq userId) and
                            (UnitAssignments.tuntasId eq tuntasId) and
                            UnitAssignments.leftAt.isNull()
                    }) {
                        it[leftAt] = now
                    }
                    UserRanks.deleteWhere {
                        (UserRanks.userId eq userId) and
                            (UserRanks.tuntasId eq tuntasId)
                    }
                    Result.success(Unit)
                }

                result
                    .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Left tuntas")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to leave tuntas")) }
            }
        }
    }
}
