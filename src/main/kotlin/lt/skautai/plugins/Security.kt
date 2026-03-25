package lt.skautai.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.database.tables.*
import lt.skautai.models.responses.ErrorResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

fun Application.configureSecurity() {
    val config = environment.config
    val secret = config.property("jwt.secret").getString()
    val issuer = config.property("jwt.issuer").getString()
    val audience = config.property("jwt.audience").getString()
    val realm = config.property("jwt.realm").getString()

    install(Authentication) {
        jwt("auth-jwt") {
            this.realm = realm
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withAudience(audience)
                    .withIssuer(issuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("userId").asString() != null) {
                    JWTPrincipal(credential.payload)
                } else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token is invalid or expired"))
            }
        }

        jwt("auth-super-admin") {
            this.realm = realm
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withAudience(audience)
                    .withIssuer(issuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("type").asString() == "super_admin") {
                    JWTPrincipal(credential.payload)
                } else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Super admin access required"))
            }
        }
    }
}

data class ResolvedPermission(
    val permissionName: String,
    val scope: String,
    val userOrgUnitId: UUID?
)

fun resolveUserPermissions(userId: UUID, tuntasId: UUID): List<ResolvedPermission> {
    return transaction {
        val leadershipRoleIds = UserLeadershipRoles
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE") and
                        (UserLeadershipRoles.leftAt.isNull())
            }
            .map { it[UserLeadershipRoles.roleId] }

        val rankRoleIds = UserRanks
            .selectAll()
            .where {
                (UserRanks.userId eq userId) and
                        (UserRanks.tuntasId eq tuntasId)
            }
            .map { it[UserRanks.roleId] }

        val allRoleIds = leadershipRoleIds + rankRoleIds
        if (allRoleIds.isEmpty()) return@transaction emptyList()

        val orgUnitId = UserLeadershipRoles
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE") and
                        (UserLeadershipRoles.leftAt.isNull()) and
                        (UserLeadershipRoles.organizationalUnitId.isNotNull())
            }
            .firstOrNull()
            ?.get(UserLeadershipRoles.organizationalUnitId)

        RolePermissions
            .innerJoin(Permissions, { RolePermissions.permissionId }, { Permissions.id })
            .selectAll()
            .where { RolePermissions.roleId inList allRoleIds }
            .map {
                ResolvedPermission(
                    permissionName = it[Permissions.name],
                    scope = it[RolePermissions.scope],
                    userOrgUnitId = orgUnitId
                )
            }
    }
}

suspend fun RoutingContext.checkPermission(
    permissionName: String,
    tuntasId: UUID,
    targetOrgUnitId: UUID? = null
): Boolean {
    val principal = call.principal<JWTPrincipal>()
    if (principal == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated"))
        return false
    }

    val userId = try {
        UUID.fromString(principal.getClaim("userId", String::class))
    } catch (e: Exception) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
        return false
    }

    val isMember = transaction {
        UserTuntasMemberships
            .selectAll()
            .where {
                (UserTuntasMemberships.userId eq userId) and
                        (UserTuntasMemberships.tuntasId eq tuntasId) and
                        (UserTuntasMemberships.leftAt.isNull())
            }
            .firstOrNull() != null
    }

    if (!isMember) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))
        return false
    }

    val permissions = resolveUserPermissions(userId, tuntasId)

    val matchingPermission = permissions.firstOrNull { it.permissionName == permissionName }

    if (matchingPermission == null) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
        return false
    }

    if (matchingPermission.scope == "ALL") return true

    if (targetOrgUnitId == null) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
        return false
    }

    if (matchingPermission.userOrgUnitId == null) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
        return false
    }

    if (matchingPermission.userOrgUnitId != targetOrgUnitId) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
        return false
    }

    return true
}