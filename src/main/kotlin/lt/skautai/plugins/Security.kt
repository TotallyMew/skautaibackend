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
                val userId = credential.payload.getClaim("userId").asString()
                val tokenType = credential.payload.getClaim("type").asString()
                val tokenUse = credential.payload.getClaim("tokenUse").asString()
                if (userId == null || tokenType != "user" || tokenUse != "access") {
                    return@validate null
                }
                val parsedUserId = runCatching { UUID.fromString(userId) }.getOrNull() ?: return@validate null
                val activeUser = transaction {
                    Users.selectAll()
                        .where { (Users.id eq parsedUserId) and Users.deletedAt.isNull() }
                        .firstOrNull() != null
                }
                if (!activeUser) return@validate null
                JWTPrincipal(credential.payload)
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
                val tokenUse = credential.payload.getClaim("tokenUse").asString()
                if (credential.payload.getClaim("type").asString() != "super_admin" || tokenUse != "access") {
                    return@validate null
                }
                val adminId = credential.payload.getClaim("userId").asString()
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@validate null
                JWTPrincipal(credential.payload)
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
    val userOrgUnitIds: Set<UUID>
)

fun resolveUserPermissions(userId: UUID, tuntasId: UUID): List<ResolvedPermission> {
    return transaction {
        val isActiveTuntasMember = UserTuntasMemberships
            .selectAll()
            .where {
                (UserTuntasMemberships.userId eq userId) and
                        (UserTuntasMemberships.tuntasId eq tuntasId) and
                        (UserTuntasMemberships.leftAt.isNull())
            }
            .firstOrNull() != null

        if (!isActiveTuntasMember) return@transaction emptyList()

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

        // Collect all unit IDs from leadership roles and active unit memberships.
        val leadershipUnitIds = UserLeadershipRoles
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE") and
                        (UserLeadershipRoles.leftAt.isNull()) and
                        (UserLeadershipRoles.organizationalUnitId.isNotNull())
            }
            .mapNotNull { it[UserLeadershipRoles.organizationalUnitId] }
            .toSet()

        val membershipUnitIds = UnitAssignments
            .selectAll()
            .where {
                (UnitAssignments.userId eq userId) and
                        (UnitAssignments.tuntasId eq tuntasId) and
                        (UnitAssignments.leftAt.isNull())
            }
            .map { it[UnitAssignments.organizationalUnitId] }
            .toSet()

        val allUnitIds = leadershipUnitIds + membershipUnitIds

        RolePermissions
            .innerJoin(Permissions, { RolePermissions.permissionId }, { Permissions.id })
            .selectAll()
            .where { RolePermissions.roleId inList allRoleIds }
            .map {
                ResolvedPermission(
                    permissionName = it[Permissions.name],
                    scope = it[RolePermissions.scope],
                    userOrgUnitIds = allUnitIds
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

    val permissions = resolveUserPermissions(userId, tuntasId)

    val matchingPermissions = permissions.filter { it.permissionName == permissionName }

    if (matchingPermissions.isEmpty()) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
        return false
    }

    if (matchingPermissions.any { it.scope == "ALL" }) return true

    if (targetOrgUnitId == null) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
        return false
    }

    val scopedOrgUnitIds = matchingPermissions.flatMap { it.userOrgUnitIds }.toSet()
    if (scopedOrgUnitIds.isEmpty()) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
        return false
    }

    if (targetOrgUnitId !in scopedOrgUnitIds) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
        return false
    }

    return true
}
