package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.RoleListResponse
import lt.skautai.models.responses.RoleResponse
import lt.skautai.services.LeadershipRoleRules
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

fun Route.rolesRoutes(apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        route("$apiPrefix/roles") {
            get {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))

                val tuntasUUID = try {
                    UUID.fromString(tuntasId)
                } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated"))
                val userId = try {
                    UUID.fromString(principal.getClaim("userId", String::class))
                } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                }

                val isActiveMember = transaction {
                    UserTuntasMemberships.selectAll()
                        .where {
                            (UserTuntasMemberships.userId eq userId) and
                                (UserTuntasMemberships.tuntasId eq tuntasUUID) and
                                UserTuntasMemberships.leftAt.isNull()
                        }
                        .firstOrNull() != null
                }

                if (!isActiveMember) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))
                }

                val roles = transaction {
                    Roles.selectAll()
                        .where { Roles.tuntasId eq tuntasUUID }
                        .filter {
                            it[Roles.roleType] != "RANK" || it[Roles.name] in supportedRankNames
                        }
                        .map {
                            RoleResponse(
                                id = it[Roles.id].toString(),
                                name = it[Roles.name],
                                roleType = it[Roles.roleType],
                                isSystemRole = it[Roles.isSystemRole],
                                canBeInvited = !LeadershipRoleRules.isTuntininkas(it[Roles.name]),
                                requiresOrganizationalUnit = LeadershipRoleRules.requiresOrganizationalUnit(it[Roles.name]),
                                allowedOrganizationalUnitTypes = LeadershipRoleRules.allowedOrganizationalUnitTypes(it[Roles.name]).sorted()
                            )
                        }
                }

                call.respond(HttpStatusCode.OK, RoleListResponse(roles = roles, total = roles.size))
            }
        }
    }
}

private val supportedRankNames = setOf(
    "Skautas",
    "Patyres skautas",
    "Vyr. skautas kandidatas",
    "Vyr. skautas",
    "Vadovas"
)
