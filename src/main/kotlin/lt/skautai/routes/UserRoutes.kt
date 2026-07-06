package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.database.tables.Tuntai
import lt.skautai.database.tables.UnitAssignments
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.UserRanks
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.database.tables.Users
import lt.skautai.database.tables.AuthRefreshSessions
import lt.skautai.models.requests.ChangeMyPasswordRequest
import lt.skautai.models.requests.UpdateMyProfileRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.models.responses.MyProfileResponse
import lt.skautai.plugins.resolveUserPermissions
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.util.*
import org.jetbrains.exposed.sql.deleteWhere
import java.util.Locale

fun Route.userRoutes(apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        route("$apiPrefix/users/me") {
            get {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val profile = transaction {
                    Users.selectAll()
                        .where { Users.id eq userId }
                        .firstOrNull()
                        ?.let {
                            MyProfileResponse(
                                userId = it[Users.id].toString(),
                                name = it[Users.name],
                                surname = it[Users.surname],
                                email = it[Users.email],
                                phone = it[Users.phone],
                                createdAt = it[Users.createdAt].toString(),
                                updatedAt = it[Users.updatedAt].toString()
                            )
                        }
                } ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))

                call.respond(HttpStatusCode.OK, profile)
            }

            put("/profile") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val request = call.receiveValidated<UpdateMyProfileRequest>()

                val normalizedName = request.name.trim()
                val normalizedSurname = request.surname.trim()
                val normalizedEmail = normalizeEmail(request.email)
                val normalizedPhone = request.phone?.trim()?.takeIf { it.isNotEmpty() }

                validateRequired(normalizedName, "Name")?.let {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
                }
                validateRequired(normalizedSurname, "Surname")?.let {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
                }
                validateEmail(normalizedEmail)?.let {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
                }
                validatePhone(normalizedPhone)?.let {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
                }

                val result = transaction {
                    val existingUser = Users.selectAll()
                        .where { Users.id eq userId }
                        .firstOrNull()
                        ?: return@transaction Result.failure(Exception("User not found"))

                    val emailTaken = Users.selectAll()
                        .where { (Users.email eq normalizedEmail) and (Users.id neq userId) }
                        .firstOrNull() != null
                    if (emailTaken) {
                        return@transaction Result.failure(Exception("Email already registered"))
                    }

                    val now = Clock.System.now()
                    Users.update({ Users.id eq userId }) {
                        it[name] = normalizedName
                        it[surname] = normalizedSurname
                        it[email] = normalizedEmail
                        it[phone] = normalizedPhone
                        it[updatedAt] = now
                    }

                    Result.success(
                        MyProfileResponse(
                            userId = existingUser[Users.id].toString(),
                            name = normalizedName,
                            surname = normalizedSurname,
                            email = normalizedEmail,
                            phone = normalizedPhone,
                            createdAt = existingUser[Users.createdAt].toString(),
                            updatedAt = now.toString()
                        )
                    )
                }

                result
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure {
                        val status = if (it.message == "User not found") HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                        call.respond(status, ErrorResponse(it.message ?: "Failed to update profile"))
                    }
            }

            put("/password") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val request = call.receiveValidated<ChangeMyPasswordRequest>()

                if (request.currentPassword.isBlank()) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Current password is required"))
                }
                validatePassword(request.newPassword)?.let {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
                }
                if (request.currentPassword == request.newPassword) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("New password must be different"))
                }

                val result = transaction {
                    val user = Users.selectAll()
                        .where { Users.id eq userId }
                        .firstOrNull()
                        ?: return@transaction Result.failure(Exception("User not found"))

                    if (!BCrypt.checkpw(request.currentPassword, user[Users.passwordHash])) {
                        return@transaction Result.failure(Exception("Invalid current password"))
                    }

                    Users.update({ Users.id eq userId }) {
                        it[passwordHash] = BCrypt.hashpw(request.newPassword, BCrypt.gensalt())
                        it[updatedAt] = Clock.System.now()
                    }
                    AuthRefreshSessions.update({
                        (AuthRefreshSessions.subjectId eq userId) and
                            (AuthRefreshSessions.subjectType eq "user") and
                            (AuthRefreshSessions.revokedAt.isNull())
                    }) {
                        it[revokedAt] = Clock.System.now()
                        it[lastUsedAt] = Clock.System.now()
                    }
                    Result.success(Unit)
                }

                result
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Password updated")) }
                    .onFailure {
                        val status = if (it.message == "User not found") HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                        call.respond(status, ErrorResponse(it.message ?: "Failed to update password"))
                    }
            }

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
                val leadershipUnitIds = resolvedPermissions
                    .flatMap { it.userOrgUnitIds }
                    .map { it.toString() }
                    .distinct()

                call.respond(HttpStatusCode.OK, mapOf(
                    "permissions" to perms,
                    "leadershipUnitIds" to leadershipUnitIds
                ))
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
                        it[termStatus] = "RESIGNED"
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
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Left tuntas")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to leave tuntas")) }
            }
        }
    }
}

private val userEmailRegex = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)

private fun normalizeEmail(email: String): String = email.trim().lowercase(Locale.ROOT)

private fun validateRequired(value: String, label: String): String? =
    if (value.isBlank()) "$label is required" else null

private fun validateEmail(email: String): String? = when {
    email.isBlank() -> "Email is required"
    !userEmailRegex.matches(email) -> "Invalid email format"
    else -> null
}

private fun validatePassword(password: String): String? = when {
    password.isBlank() -> "Password is required"
    password.length < 8 -> "Password must be at least 8 characters"
    password.any { it.isWhitespace() } -> "Password cannot contain spaces"
    !password.any { it.isLetter() } -> "Password must contain a letter"
    !password.any { it.isDigit() } -> "Password must contain a number"
    else -> null
}

private fun validatePhone(phone: String?): String? {
    if (phone.isNullOrBlank()) return null
    val normalized = phone.replace(" ", "")
    return if (!normalized.matches(Regex("^\\+?[0-9]{6,20}$"))) "Invalid phone format" else null
}
