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

    install(TenantAccessGuard)
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
                if (!activeUser || !isAccessSessionActive(credential.payload)) return@validate null
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
                val activeAdmin = transaction { SuperAdmins.selectAll().where { SuperAdmins.id eq adminId }.any() }
                if (!activeAdmin || !isAccessSessionActive(credential.payload)) return@validate null
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
        if (!isActiveTenantMember(userId, tuntasId)) return@transaction emptyList()
        val isActiveTuntasMember = UserTuntasMemberships
            .selectAll()
            .where {
                (UserTuntasMemberships.userId eq userId) and
                        (UserTuntasMemberships.tuntasId eq tuntasId) and
                        (UserTuntasMemberships.leftAt.isNull())
            }
            .firstOrNull() != null

        if (!isActiveTuntasMember) return@transaction emptyList()

        val leadershipRoleRows = UserLeadershipRoles
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        UserLeadershipRoles.effectiveNow() and
                        (UserLeadershipRoles.leftAt.isNull())
            }
            .toList()

        val rankRoleRows = UserRanks
            .selectAll()
            .where {
                (UserRanks.userId eq userId) and
                        (UserRanks.tuntasId eq tuntasId)
            }
            .toList()

        val membershipUnitIds = UnitAssignments
            .selectAll()
            .where {
                (UnitAssignments.userId eq userId) and
                        (UnitAssignments.tuntasId eq tuntasId) and
                        (UnitAssignments.leftAt.isNull())
            }
            .map { it[UnitAssignments.organizationalUnitId] }
            .toSet()

        val roleUnitIds = mutableMapOf<UUID, MutableSet<UUID>>()
        leadershipRoleRows.forEach { row ->
            val units = roleUnitIds.getOrPut(row[UserLeadershipRoles.roleId]) { mutableSetOf() }
            row[UserLeadershipRoles.organizationalUnitId]?.let(units::add)
        }
        rankRoleRows.forEach { row ->
            roleUnitIds.getOrPut(row[UserRanks.roleId]) { mutableSetOf() }
                .addAll(membershipUnitIds)
        }

        if (roleUnitIds.isEmpty()) return@transaction emptyList()

        RolePermissions
            .innerJoin(Permissions, { RolePermissions.permissionId }, { Permissions.id })
            .selectAll()
            .where { RolePermissions.roleId inList roleUnitIds.keys.toList() }
            .map {
                ResolvedPermission(
                    permissionName = it[Permissions.name],
                    scope = it[RolePermissions.scope],
                    userOrgUnitIds = roleUnitIds[it[RolePermissions.roleId]].orEmpty()
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

/** Checks the exact refresh session that issued this access token. Rotation also retires old access. */
fun isAccessSessionActive(payload: com.auth0.jwt.interfaces.Payload): Boolean {
    val sessionId = payload.getClaim("sessionId").asString()?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return false
    val subjectId = payload.getClaim("userId").asString()?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return false
    val type = payload.getClaim("type").asString() ?: return false
    if (payload.expiresAt?.time?.let { it <= System.currentTimeMillis() } != false) return false
    return transaction {
        AuthRefreshSessions.selectAll().where {
            (AuthRefreshSessions.id eq sessionId) and (AuthRefreshSessions.subjectId eq subjectId) and
                (AuthRefreshSessions.subjectType eq type) and AuthRefreshSessions.revokedAt.isNull() and
                (AuthRefreshSessions.expiresAt greater kotlinx.datetime.Clock.System.now())
        }.any()
    }
}

fun isActiveTenantMember(userId: UUID, tuntasId: UUID): Boolean = transaction {
    UserTuntasMemberships.innerJoin(Tuntai, { UserTuntasMemberships.tuntasId }, { Tuntai.id })
        .innerJoin(Users, { UserTuntasMemberships.userId }, { Users.id })
        .selectAll().where {
            (UserTuntasMemberships.userId eq userId) and (UserTuntasMemberships.tuntasId eq tuntasId) and
                Users.deletedAt.isNull() and UserTuntasMemberships.leftAt.isNull() and (Tuntai.status inList listOf("ACTIVE", "APPROVED"))
        }.any()
}

private val TenantAccessGuard = createApplicationPlugin("TenantAccessGuard") {
    on(AuthenticationChecked) { call ->
        val principal = call.principal<JWTPrincipal>() ?: return@on
        if (principal.getClaim("type", String::class) != "user") return@on
        val path = call.request.local.uri.substringBefore('?').replaceFirst(Regex("^/api/v1/"), "/api/")
        // Account recovery/profile/tenant selection must remain usable while a tenant is pending.
        if (path == "/api/users/me" || path.startsWith("/api/users/me/")) return@on
        val rawTenant = call.request.headers["X-Tuntas-Id"] ?: return@on
        val tenant = runCatching { UUID.fromString(rawTenant) }.getOrNull()
        val user = runCatching { UUID.fromString(principal.getClaim("userId", String::class)) }.getOrNull()
        if (tenant == null || user == null || !isActiveTenantMember(user, tenant)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Active tuntas membership required"))
        }
    }
}
