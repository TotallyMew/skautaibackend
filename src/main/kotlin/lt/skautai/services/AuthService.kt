package lt.skautai.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import lt.skautai.database.tables.*
import lt.skautai.models.requests.LoginRequest
import lt.skautai.models.requests.ForgotPasswordRequest
import lt.skautai.models.requests.ResetPasswordRequest
import lt.skautai.models.requests.RegisterTuntininkasRequest
import lt.skautai.models.requests.RegisterWithInviteRequest
import lt.skautai.models.responses.MessageResponse
import lt.skautai.models.responses.TokenResponse
import lt.skautai.models.responses.TuntasInfo
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*
import lt.skautai.database.tables.UnitAssignments
class AuthService(
    private val environment: ApplicationEnvironment,
    private val emailService: EmailService = ResendEmailService()
) {
    companion object {
        private const val accessTokenLifetimeMs = 8 * 60 * 60 * 1000L
        private const val refreshTokenLifetimeMs = 30L * 24 * 60 * 60 * 1000
        private const val maxFailedAttempts = 5
        private const val rateLimitWindowMs = 15 * 60 * 1000L
        private const val blockDurationMs = 15 * 60 * 1000L
        private const val passwordResetLifetimeMs = 60 * 60 * 1000L
        private const val passwordResetRequestWindowMs = 15 * 60 * 1000L
        private const val maxPasswordResetRequestsPerWindow = 3
        private const val nameMinLength = 2
        private const val nameMaxLength = 100
        private const val surnameMinLength = 2
        private const val surnameMaxLength = 100
        private const val emailMaxLength = 255
        private const val passwordMinLength = 8
        private const val passwordMaxLength = 128
        private const val phoneMaxLength = 20
        private const val tuntasNameMinLength = 2
        private const val tuntasNameMaxLength = 100
        private const val inviteCodeMaxLength = 20
    }

    private val secret = environment.config.property("jwt.secret").getString()
    private val issuer = environment.config.property("jwt.issuer").getString()
    private val audience = environment.config.property("jwt.audience").getString()
    private val emailRegex = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
    private val personNameRegex = Regex("^[\\p{L}][\\p{L} '\\-]*[\\p{L}]$")
    private val tuntasNameRegex = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} .,'()\\-]*$")
    private val phoneRegex = Regex("^\\+?[0-9][0-9 ()\\-]*$")
    private val dummyPasswordHash = BCrypt.hashpw("invalid-login-password-placeholder", BCrypt.gensalt())
    private val allowedKrastai = setOf(
        "Alytaus",
        "Kauno",
        "Klaipėdos",
        "Marijampolės",
        "Šiaulių",
        "Tauragės",
        "Telšių",
        "Utenos",
        "Vilniaus"
    )

    // role name -> role_type
    private val systemRoles = mapOf(
        "Tuntininkas" to "LEADERSHIP",
        "Tuntininko pavaduotojas" to "LEADERSHIP",
        "Inventorininkas" to "LEADERSHIP",
        "Finansininkas" to "LEADERSHIP",
        "Draugininkas" to "LEADERSHIP",
        "Draugininko pavaduotojas" to "LEADERSHIP",
        "Gildijos pirmininkas" to "LEADERSHIP",
        "Gildijos pirmininko pavaduotojas" to "LEADERSHIP",
        "Vyr. skautu draugoves draugininkas" to "LEADERSHIP",
        "Vyr. skautu draugoves draugininko pavaduotojas" to "LEADERSHIP",
        "Vyr. skautu burelio pirmininkas" to "LEADERSHIP",
        "Vyr. skautu burelio pirmininko pavaduotojas" to "LEADERSHIP",
        "Vyr. skauciu draugoves draugininkas" to "LEADERSHIP",
        "Vyr. skauciu draugoves draugininko pavaduotojas" to "LEADERSHIP",
        "Vyr. skauciu burelio pirmininkas" to "LEADERSHIP",
        "Vyr. skauciu burelio pirmininko pavaduotojas" to "LEADERSHIP",
        "Skautas" to "RANK",
        "Patyres skautas" to "RANK",
        "Vyr. skautas kandidatas" to "RANK",
        "Vyr. skautas" to "RANK",
        "Vadovas" to "RANK"
    )

    fun registerTuntininkas(request: RegisterTuntininkasRequest): Result<TokenResponse> {
        return transaction {
            val name = request.name.trim()
            val surname = request.surname.trim()
            val email = normalizeEmail(request.email)
            val phone = normalizePhone(request.phone)
            val tuntasName = request.tuntasName.trim()
            val tuntasKrastas = request.tuntasKrastas?.trim().orEmpty()

            validateName(name)?.let { return@transaction Result.failure(Exception(it)) }
            validateSurname(surname)?.let { return@transaction Result.failure(Exception(it)) }
            validateEmail(email)?.let { return@transaction Result.failure(Exception(it)) }
            validatePassword(request.password)?.let { return@transaction Result.failure(Exception(it)) }
            validatePhone(phone)?.let { return@transaction Result.failure(Exception(it)) }
            validateTuntasName(tuntasName)?.let { return@transaction Result.failure(Exception(it)) }
            validateKrastas(tuntasKrastas)?.let { return@transaction Result.failure(Exception(it)) }

            val existingUser = Users.selectAll()
                .where { Users.email eq email }
                .firstOrNull()
            if (existingUser != null) {
                return@transaction Result.failure(Exception("Email already registered"))
            }

            val existingTuntas = Tuntai.selectAll()
                .where { Tuntai.name.lowerCase() eq tuntasName.lowercase(Locale.ROOT) }
                .firstOrNull()
            if (existingTuntas != null) {
                return@transaction Result.failure(Exception("Tuntas name already exists"))
            }

            val passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())

            val userId = Users.insert {
                it[Users.name] = name
                it[Users.surname] = surname
                it[Users.email] = email
                it[Users.passwordHash] = passwordHash
                it[Users.phone] = phone
            } get Users.id

            val tuntasId = Tuntai.insert {
                it[Tuntai.name] = tuntasName
                it[Tuntai.krastas] = tuntasKrastas
                it[Tuntai.contactEmail] = email
                it[Tuntai.status] = "PENDING"
            } get Tuntai.id

            UserTuntasMemberships.insert {
                it[this.userId] = userId
                it[this.tuntasId] = tuntasId
            }

            // Seed all system roles with correct role_type
            for ((roleName, roleType) in systemRoles) {
                Roles.insert {
                    it[Roles.tuntasId] = tuntasId
                    it[Roles.name] = roleName
                    it[Roles.isSystemRole] = true
                    it[Roles.roleType] = roleType
                }
            }
            PermissionSeeder.seedRolePermissions(tuntasId)
            val tuntininkasRoleId = Roles.selectAll()
                .where { (Roles.name eq "Tuntininkas") and (Roles.tuntasId eq tuntasId) }
                .first()[Roles.id]

            // Tuntininkas is a LEADERSHIP role
            UserLeadershipRoles.insert {
                it[this.userId] = userId
                it[roleId] = tuntininkasRoleId
                it[this.tuntasId] = tuntasId
            }

            VadovasRankSupport.ensureVadovasRank(
                userId = userId,
                tuntasId = tuntasId,
                assignedByUserId = userId
            )

            val token = generateAccessToken(userId.toString(), email, "user")
            val refreshToken = issueRefreshToken(userId, email, "user")
            Result.success(
                TokenResponse(
                    token = token,
                    refreshToken = refreshToken,
                    userId = userId.toString(),
                    email = email,
                    name = name
                )
            )
        }
    }

    fun registerWithInvite(request: RegisterWithInviteRequest): Result<TokenResponse> {
        return transaction {
            val name = request.name.trim()
            val surname = request.surname.trim()
            val email = normalizeEmail(request.email)
            val phone = normalizePhone(request.phone)
            val inviteCode = request.inviteCode.trim()

            validateName(name)?.let { return@transaction Result.failure(Exception(it)) }
            validateSurname(surname)?.let { return@transaction Result.failure(Exception(it)) }
            validateEmail(email)?.let { return@transaction Result.failure(Exception(it)) }
            validatePassword(request.password)?.let { return@transaction Result.failure(Exception(it)) }
            validatePhone(phone)?.let { return@transaction Result.failure(Exception(it)) }
            validateInviteCode(inviteCode)?.let { return@transaction Result.failure(Exception(it)) }

            val existingUser = Users.selectAll()
                .where { Users.email eq email }
                .firstOrNull()
            if (existingUser != null) {
                return@transaction Result.failure(Exception("Email already registered"))
            }

            val invite = Invitations.selectAll()
                .where { Invitations.code eq inviteCode }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Invalid invite code"))

            if (invite[Invitations.usedByUserId] != null) {
                return@transaction Result.failure(Exception("Invite code already used"))
            }

            val now = kotlinx.datetime.Clock.System.now()
            if (invite[Invitations.expiresAt] < now) {
                return@transaction Result.failure(Exception("Invite code expired"))
            }

            val tuntasId = invite[Invitations.tuntasId]
            val roleId = invite[Invitations.roleId]
            val orgUnitId = invite[Invitations.organizationalUnitId]

            val tuntas = Tuntai.selectAll()
                .where { Tuntai.id eq tuntasId }
                .firstOrNull()
            if (tuntas == null || tuntas[Tuntai.status] != "ACTIVE") {
                return@transaction Result.failure(Exception("Tuntas is not active"))
            }

            // Determine role type
            val role = Roles.selectAll()
                .where { Roles.id eq roleId }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Role not found"))

            val passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())

            val userId = Users.insert {
                it[Users.name] = name
                it[Users.surname] = surname
                it[Users.email] = email
                it[Users.passwordHash] = passwordHash
                it[Users.phone] = phone
            } get Users.id

            UserTuntasMemberships.insert {
                it[this.userId] = userId
                it[this.tuntasId] = tuntasId
            }

            // Insert into correct table based on role type
            when (role[Roles.roleType]) {
                "LEADERSHIP" -> {
                    LeadershipRoleRules.validatePrincipalUnitLeaderSlot(roleId, tuntasId, orgUnitId)
                        ?.let { return@transaction Result.failure(Exception(it)) }

                    UserLeadershipRoles.insert {
                        it[this.userId] = userId
                        it[this.roleId] = roleId
                        it[this.tuntasId] = tuntasId
                        it[organizationalUnitId] = orgUnitId
                        it[assignedByUserId] = invite[Invitations.createdByUserId]
                    }
                    VadovasRankSupport.ensureVadovasRank(
                        userId = userId,
                        tuntasId = tuntasId,
                        assignedByUserId = invite[Invitations.createdByUserId]
                    )
                }
                "RANK" -> UserRanks.insert {
                    it[this.userId] = userId
                    it[this.roleId] = roleId
                    it[this.tuntasId] = tuntasId
                    it[assignedByUserId] = invite[Invitations.createdByUserId]
                }
                else -> return@transaction Result.failure(Exception("Unknown role type"))
            }
            // If the invite is scoped to an organizational unit, assign the user to that unit
            if (orgUnitId != null) {
                UnitAssignments.insert {
                    it[this.userId] = userId
                    it[this.organizationalUnitId] = orgUnitId
                    it[this.tuntasId] = tuntasId
                    it[assignmentType] = "MEMBER"
                    it[assignedByUserId] = invite[Invitations.createdByUserId]
                }
            }
            Invitations.update({ Invitations.id eq invite[Invitations.id] }) {
                it[usedByUserId] = userId
                it[usedAt] = now
            }

            val token = generateAccessToken(userId.toString(), email, "user")
            val refreshToken = issueRefreshToken(userId, email, "user")
            val tuntai = getActiveTuntaiForUser(userId)
            Result.success(
                TokenResponse(
                    token = token,
                    refreshToken = refreshToken,
                    userId = userId.toString(),
                    email = email,
                    name = name,
                    tuntai = tuntai
                )
            )
        }
    }

    fun login(request: LoginRequest): Result<TokenResponse> {
        val email = normalizeEmail(request.email)
        val rateLimitKey = "login:$email"
        loginRateLimitError(rateLimitKey)?.let { return Result.failure(Exception(it)) }

        return transaction {
            val user = Users.selectAll()
                .where { (Users.email eq email) and Users.deletedAt.isNull() }
                .firstOrNull()
            val admin = SuperAdmins.selectAll()
                .where { SuperAdmins.email eq email }
                .firstOrNull()

            val userPasswordMatches = BCrypt.checkpw(request.password, user?.get(Users.passwordHash) ?: dummyPasswordHash)
            val adminPasswordMatches = BCrypt.checkpw(request.password, admin?.get(SuperAdmins.passwordHash) ?: dummyPasswordHash)

            if (user != null && userPasswordMatches) {
                val token = generateAccessToken(
                    user[Users.id].toString(),
                    user[Users.email],
                    "user"
                )
                val refreshToken = issueRefreshToken(
                    user[Users.id],
                    user[Users.email],
                    "user"
                )
                val tuntai = getActiveTuntaiForUser(user[Users.id])
                return@transaction Result.success(
                    TokenResponse(
                        token = token,
                        refreshToken = refreshToken,
                        userId = user[Users.id].toString(),
                        email = user[Users.email],
                        name = user[Users.name],
                        tuntai = tuntai
                    )
                )
            }

            if (admin != null && adminPasswordMatches) {
                val token = generateAccessToken(
                    admin[SuperAdmins.id].toString(),
                    admin[SuperAdmins.email],
                    "super_admin"
                )
                val refreshToken = issueRefreshToken(
                    admin[SuperAdmins.id],
                    admin[SuperAdmins.email],
                    "super_admin"
                )
                return@transaction Result.success(
                    TokenResponse(
                        token = token,
                        refreshToken = refreshToken,
                        userId = admin[SuperAdmins.id].toString(),
                        email = admin[SuperAdmins.email],
                        name = admin[SuperAdmins.name],
                        type = "super_admin"
                    )
                )
            }

            Result.failure(Exception("Invalid email or password"))
        }.also { result ->
            if (result.isSuccess) {
                clearLoginFailures(rateLimitKey)
            } else {
                recordLoginFailure(rateLimitKey)
            }
        }
    }

    fun loginSuperAdmin(request: LoginRequest): Result<TokenResponse> {
        val email = normalizeEmail(request.email)
        val rateLimitKey = "super_admin:$email"
        loginRateLimitError(rateLimitKey)?.let { return Result.failure(Exception(it)) }

        return transaction {
            val admin = SuperAdmins.selectAll()
                .where { SuperAdmins.email eq email }
                .firstOrNull()
            val passwordMatches = BCrypt.checkpw(request.password, admin?.get(SuperAdmins.passwordHash) ?: dummyPasswordHash)

            if (admin == null || !passwordMatches) {
                return@transaction Result.failure(Exception("Invalid email or password"))
            }

            val token = generateAccessToken(
                admin[SuperAdmins.id].toString(),
                admin[SuperAdmins.email],
                "super_admin"
            )
            val refreshToken = issueRefreshToken(
                admin[SuperAdmins.id],
                admin[SuperAdmins.email],
                "super_admin"
            )
            Result.success(
                TokenResponse(
                    token = token,
                    refreshToken = refreshToken,
                    userId = admin[SuperAdmins.id].toString(),
                    email = admin[SuperAdmins.email],
                    name = admin[SuperAdmins.name],
                    type = "super_admin"
                )
            )
        }.also { result ->
            if (result.isSuccess) {
                clearLoginFailures(rateLimitKey)
            } else {
                recordLoginFailure(rateLimitKey)
            }
        }
    }

    fun refreshAccessToken(refreshToken: String): Result<TokenResponse> {
        val decoded = try {
            JWT.require(Algorithm.HMAC256(secret))
                .withAudience(audience)
                .withIssuer(issuer)
                .build()
                .verify(refreshToken)
        } catch (_: Exception) {
            return Result.failure(Exception("Invalid refresh token"))
        }

        if (decoded.getClaim("tokenUse").asString() != "refresh") {
            return Result.failure(Exception("Invalid refresh token"))
        }

        val userId = decoded.getClaim("userId").asString()
            ?: return Result.failure(Exception("Invalid refresh token"))
        val email = decoded.getClaim("email").asString()
            ?: return Result.failure(Exception("Invalid refresh token"))
        val type = decoded.getClaim("type").asString()
            ?: return Result.failure(Exception("Invalid refresh token"))
        val userUuid = runCatching { UUID.fromString(userId) }.getOrNull()
            ?: return Result.failure(Exception("Invalid refresh token"))

        val sessionId = decoded.id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Result.failure(Exception("Invalid refresh token"))
        val tokenHash = hashToken(refreshToken)

        return transaction {
            val now = kotlinx.datetime.Clock.System.now()
            val session = AuthRefreshSessions.selectAll()
                .where {
                    (AuthRefreshSessions.id eq sessionId) and
                        (AuthRefreshSessions.subjectId eq userUuid) and
                        (AuthRefreshSessions.subjectType eq type) and
                        (AuthRefreshSessions.tokenHash eq tokenHash)
                }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Invalid refresh token"))

            if (
                session[AuthRefreshSessions.revokedAt] != null ||
                session[AuthRefreshSessions.expiresAt] <= now
            ) {
                return@transaction Result.failure(Exception("Invalid refresh token"))
            }

            when (type) {
                "user" -> {
                    val user = Users.selectAll()
                        .where { (Users.id eq userUuid) and Users.deletedAt.isNull() }
                        .firstOrNull()
                        ?: return@transaction Result.failure(Exception("User not found"))

                    val tuntai = getActiveTuntaiForUser(userUuid)
                    val rotatedRefreshToken = issueRefreshToken(userUuid, user[Users.email], type)
                    revokeSession(sessionId, rotatedRefreshToken, now)
                    Result.success(
                        TokenResponse(
                            token = generateAccessToken(userId, email, type),
                            refreshToken = rotatedRefreshToken,
                            userId = userId,
                            email = user[Users.email],
                            name = user[Users.name],
                            type = type,
                            tuntai = tuntai
                        )
                    )
                }

                "super_admin" -> {
                    val admin = SuperAdmins.selectAll()
                        .where { SuperAdmins.id eq userUuid }
                        .firstOrNull()
                        ?: return@transaction Result.failure(Exception("Super admin not found"))

                    val rotatedRefreshToken = issueRefreshToken(userUuid, admin[SuperAdmins.email], type)
                    revokeSession(sessionId, rotatedRefreshToken, now)
                    Result.success(
                        TokenResponse(
                            token = generateAccessToken(userId, email, type),
                            refreshToken = rotatedRefreshToken,
                            userId = userId,
                            email = admin[SuperAdmins.email],
                            name = admin[SuperAdmins.name],
                            type = type
                        )
                    )
                }

                else -> Result.failure(Exception("Invalid refresh token"))
            }
        }
    }

    fun logout(refreshToken: String): Result<Unit> {
        val decoded = runCatching {
            JWT.require(Algorithm.HMAC256(secret))
                .withAudience(audience)
                .withIssuer(issuer)
                .build()
                .verify(refreshToken)
        }.getOrNull() ?: return Result.success(Unit)

        val sessionId = decoded.id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Result.success(Unit)
        val tokenHash = hashToken(refreshToken)
        transaction {
            AuthRefreshSessions.update({
                (AuthRefreshSessions.id eq sessionId) and
                    (AuthRefreshSessions.tokenHash eq tokenHash) and
                    (AuthRefreshSessions.revokedAt.isNull())
            }) {
                it[revokedAt] = kotlinx.datetime.Clock.System.now()
                it[lastUsedAt] = kotlinx.datetime.Clock.System.now()
            }
        }
        return Result.success(Unit)
    }

    fun requestPasswordReset(request: ForgotPasswordRequest): Result<Unit> {
        val email = normalizeEmail(request.email)
        validateEmail(email)?.let { return Result.failure(Exception(it)) }

        data class ResetSubject(val id: UUID, val type: String, val name: String)
        val subject = transaction {
            Users.selectAll().where { (Users.email eq email) and Users.deletedAt.isNull() }.firstOrNull()?.let {
                ResetSubject(it[Users.id], "user", it[Users.name])
            } ?: SuperAdmins.selectAll().where { SuperAdmins.email eq email }.firstOrNull()?.let {
                ResetSubject(it[SuperAdmins.id], "super_admin", it[SuperAdmins.name])
            }
        } ?: return Result.success(Unit)

        val publicBaseUrl = setting("PASSWORD_RESET_PUBLIC_BASE_URL")
        if (publicBaseUrl == null) {
            environment.log.error("Password reset requested but PASSWORD_RESET_PUBLIC_BASE_URL is not configured")
            return Result.success(Unit)
        }
        val rawToken = randomUrlToken()
        val tokenId = UUID.randomUUID()
        val now = kotlinx.datetime.Clock.System.now()
        val windowStart = kotlinx.datetime.Instant.fromEpochMilliseconds(
            now.toEpochMilliseconds() - passwordResetRequestWindowMs
        )
        val created = transaction {
            exec("SELECT pg_advisory_xact_lock(${passwordResetThrottleLockKey(subject.id, subject.type)})")
            val tooManyRequests = PasswordResetTokens.selectAll().where {
                (PasswordResetTokens.subjectId eq subject.id) and
                    (PasswordResetTokens.subjectType eq subject.type) and
                    (PasswordResetTokens.createdAt greaterEq windowStart)
            }.count() >= maxPasswordResetRequestsPerWindow
            if (tooManyRequests) {
                return@transaction false
            }
            PasswordResetTokens.update({
                (PasswordResetTokens.subjectId eq subject.id) and
                    (PasswordResetTokens.subjectType eq subject.type) and
                    (PasswordResetTokens.usedAt.isNull())
            }) {
                it[usedAt] = now
            }
            PasswordResetTokens.insert {
                it[id] = tokenId
                it[subjectId] = subject.id
                it[subjectType] = subject.type
                it[tokenHash] = hashToken(rawToken)
                it[expiresAt] = kotlinx.datetime.Instant.fromEpochMilliseconds(
                    now.toEpochMilliseconds() + passwordResetLifetimeMs
                )
                it[createdAt] = now
            }
            true
        }
        if (!created) return Result.success(Unit)

        val resetUrl = "${publicBaseUrl.trimEnd('/')}/password-reset/open?token=${
            URLEncoder.encode(rawToken, StandardCharsets.UTF_8)
        }"
        emailService.sendPasswordReset(email, subject.name, resetUrl)
            .onFailure {
                transaction { PasswordResetTokens.deleteWhere { id eq tokenId } }
                environment.log.error("Failed to deliver password reset email", it)
        }
        return Result.success(Unit)
    }

    private fun passwordResetThrottleLockKey(subjectId: UUID, subjectType: String): Long =
        subjectId.mostSignificantBits xor subjectId.leastSignificantBits xor subjectType.hashCode().toLong()

    fun resetPassword(request: ResetPasswordRequest): Result<Unit> {
        val token = request.token.trim()
        if (token.isBlank()) return Result.failure(Exception("Password reset token is required"))
        validatePassword(request.newPassword)?.let { return Result.failure(Exception(it)) }
        val now = kotlinx.datetime.Clock.System.now()

        return transaction {
            val row = PasswordResetTokens.selectAll()
                .where { PasswordResetTokens.tokenHash eq hashToken(token) }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Password reset link is invalid or expired"))
            if (row[PasswordResetTokens.usedAt] != null || row[PasswordResetTokens.expiresAt] <= now) {
                return@transaction Result.failure(Exception("Password reset link is invalid or expired"))
            }

            val subjectId = row[PasswordResetTokens.subjectId]
            val subjectType = row[PasswordResetTokens.subjectType]
            val passwordHash = BCrypt.hashpw(request.newPassword, BCrypt.gensalt())
            val changed = when (subjectType) {
                "user" -> Users.update({ Users.id eq subjectId }) {
                    it[Users.passwordHash] = passwordHash
                    it[updatedAt] = now
                }
                "super_admin" -> SuperAdmins.update({ SuperAdmins.id eq subjectId }) {
                    it[SuperAdmins.passwordHash] = passwordHash
                }
                else -> 0
            }
            if (changed == 0) {
                return@transaction Result.failure(Exception("Password reset link is invalid or expired"))
            }

            PasswordResetTokens.update({
                (PasswordResetTokens.subjectId eq subjectId) and
                    (PasswordResetTokens.subjectType eq subjectType) and
                    (PasswordResetTokens.usedAt.isNull())
            }) {
                it[usedAt] = now
            }
            AuthRefreshSessions.update({
                (AuthRefreshSessions.subjectId eq subjectId) and
                    (AuthRefreshSessions.subjectType eq subjectType) and
                    (AuthRefreshSessions.revokedAt.isNull())
            }) {
                it[revokedAt] = now
                it[lastUsedAt] = now
            }
            Result.success(Unit)
        }
    }

    fun revokeAllSessions(subjectId: UUID, subjectType: String) {
        transaction {
            val now = kotlinx.datetime.Clock.System.now()
            AuthRefreshSessions.update({
                (AuthRefreshSessions.subjectId eq subjectId) and
                    (AuthRefreshSessions.subjectType eq subjectType) and
                    (AuthRefreshSessions.revokedAt.isNull())
            }) {
                it[revokedAt] = now
                it[lastUsedAt] = now
            }
        }
    }

    fun seedSuperAdmin(request: LoginRequest): Result<MessageResponse> {
        val email = normalizeEmail(request.email)
        validateEmail(email)?.let { return Result.failure(Exception(it)) }
        validatePassword(request.password)?.let { return Result.failure(Exception(it)) }

        return transaction {
            val existingAdmin = SuperAdmins.selectAll().firstOrNull()
            if (existingAdmin != null) {
                return@transaction Result.failure(Exception("Super admin already exists"))
            }

            val passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())

            SuperAdmins.insert {
                it[name] = "Super Admin"
                it[this.email] = email
                it[this.passwordHash] = passwordHash
            }

            Result.success(MessageResponse("Super admin created successfully"))
        }
    }

    private fun getActiveTuntaiForUser(userId: UUID): List<TuntasInfo> {
        return UserTuntasMemberships
            .innerJoin(Tuntai, { UserTuntasMemberships.tuntasId }, { Tuntai.id })
            .selectAll()
            .where {
                (UserTuntasMemberships.userId eq userId) and
                        (UserTuntasMemberships.leftAt.isNull()) and
                        (Tuntai.status eq "ACTIVE")
            }
            .map {
                TuntasInfo(
                    id = it[Tuntai.id].toString(),
                    name = it[Tuntai.name],
                    krastas = it[Tuntai.krastas] ?: "",
                    contactEmail = it[Tuntai.contactEmail] ?: "",
                    status = it[Tuntai.status]
                )
            }
    }

    private fun normalizeEmail(email: String): String = email.trim().lowercase(Locale.ROOT)

    private fun normalizePhone(phone: String?): String? = phone?.trim()?.takeIf { it.isNotBlank() }

    private fun validateName(name: String): String? = validatePersonName(
        value = name,
        requiredMessage = "Name is required",
        tooShortMessage = "Name must be at least $nameMinLength characters",
        tooLongMessage = "Name must be at most $nameMaxLength characters",
        invalidMessage = "Name contains invalid characters",
        minLength = nameMinLength,
        maxLength = nameMaxLength
    )

    private fun validateSurname(surname: String): String? = validatePersonName(
        value = surname,
        requiredMessage = "Surname is required",
        tooShortMessage = "Surname must be at least $surnameMinLength characters",
        tooLongMessage = "Surname must be at most $surnameMaxLength characters",
        invalidMessage = "Surname contains invalid characters",
        minLength = surnameMinLength,
        maxLength = surnameMaxLength
    )

    private fun validateEmail(email: String): String? {
        return when {
            email.isBlank() -> "Email is required"
            email.length > emailMaxLength -> "Email must be at most $emailMaxLength characters"
            !emailRegex.matches(email) -> "Invalid email format"
            else -> null
        }
    }

    private fun validatePassword(password: String): String? {
        return when {
            password.isBlank() -> "Password is required"
            password.length < passwordMinLength -> "Password must be at least $passwordMinLength characters"
            password.length > passwordMaxLength -> "Password must be at most $passwordMaxLength characters"
            password.any { it.isWhitespace() } -> "Password cannot contain spaces"
            !password.any { it.isLetter() } -> "Password must contain a letter"
            !password.any { it.isDigit() } -> "Password must contain a number"
            else -> null
        }
    }

    private fun validatePhone(phone: String?): String? {
        if (phone == null) return null
        val digitCount = phone.count { it.isDigit() }
        return when {
            phone.length > phoneMaxLength -> "Phone must be at most $phoneMaxLength characters"
            !phoneRegex.matches(phone) -> "Invalid phone format"
            digitCount < 5 -> "Phone must contain at least 5 digits"
            digitCount > 15 -> "Phone must contain at most 15 digits"
            else -> null
        }
    }

    private fun validateTuntasName(tuntasName: String): String? {
        return when {
            tuntasName.isBlank() -> "Tuntas name is required"
            tuntasName.length < tuntasNameMinLength -> "Tuntas name must be at least $tuntasNameMinLength characters"
            tuntasName.length > tuntasNameMaxLength -> "Tuntas name must be at most $tuntasNameMaxLength characters"
            !tuntasName.any { it.isLetter() } -> "Tuntas name must contain a letter"
            !tuntasNameRegex.matches(tuntasName) -> "Tuntas name contains invalid characters"
            else -> null
        }
    }

    private fun validateKrastas(krastas: String): String? {
        return when {
            krastas.isBlank() -> "Krastas is required"
            krastas !in allowedKrastai -> "Invalid krastas"
            else -> null
        }
    }

    private fun validateInviteCode(inviteCode: String): String? {
        return when {
            inviteCode.isBlank() -> "Invite code is required"
            inviteCode.length > inviteCodeMaxLength -> "Invite code must be at most $inviteCodeMaxLength characters"
            else -> null
        }
    }

    private fun validatePersonName(
        value: String,
        requiredMessage: String,
        tooShortMessage: String,
        tooLongMessage: String,
        invalidMessage: String,
        minLength: Int,
        maxLength: Int
    ): String? {
        return when {
            value.isBlank() -> requiredMessage
            value.length < minLength -> tooShortMessage
            value.length > maxLength -> tooLongMessage
            !personNameRegex.matches(value) -> invalidMessage
            else -> null
        }
    }

    private fun generateAccessToken(userId: String, email: String, type: String): String {
        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withClaim("type", type)
            .withClaim("tokenUse", "access")
            .withExpiresAt(Date(System.currentTimeMillis() + accessTokenLifetimeMs))
            .sign(Algorithm.HMAC256(secret))
    }

    private fun issueRefreshToken(userId: UUID, email: String, type: String): String {
        val sessionId = UUID.randomUUID()
        val now = kotlinx.datetime.Clock.System.now()
        val expiresAt = kotlinx.datetime.Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + refreshTokenLifetimeMs)
        val token = JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withJWTId(sessionId.toString())
            .withClaim("userId", userId.toString())
            .withClaim("email", email)
            .withClaim("type", type)
            .withClaim("tokenUse", "refresh")
            .withExpiresAt(Date(expiresAt.toEpochMilliseconds()))
            .sign(Algorithm.HMAC256(secret))
        AuthRefreshSessions.insert {
            it[id] = sessionId
            it[subjectId] = userId
            it[subjectType] = type
            it[tokenHash] = hashToken(token)
            it[AuthRefreshSessions.expiresAt] = expiresAt
            it[createdAt] = now
        }
        return token
    }

    private fun revokeSession(
        sessionId: UUID,
        replacementToken: String,
        now: kotlinx.datetime.Instant
    ) {
        val replacementId = JWT.decode(replacementToken).id?.let(UUID::fromString)
        AuthRefreshSessions.update({ AuthRefreshSessions.id eq sessionId }) {
            it[revokedAt] = now
            it[lastUsedAt] = now
            it[replacedBySessionId] = replacementId
        }
    }

    private fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun randomUrlToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun setting(name: String): String? =
        (System.getenv(name) ?: System.getProperty(name))?.trim()?.takeIf(String::isNotBlank)

    private fun nowPlus(milliseconds: Long): kotlinx.datetime.Instant {
        val now = kotlinx.datetime.Clock.System.now()
        return kotlinx.datetime.Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + milliseconds)
    }

    private fun loginRateLimitError(rateLimitKey: String): String? {
        return transaction {
            val now = kotlinx.datetime.Clock.System.now()
            val row = AuthLoginThrottles.selectAll()
                .where { AuthLoginThrottles.key eq rateLimitKey }
                .firstOrNull()
                ?: return@transaction null
            val blockedUntil = row[AuthLoginThrottles.blockedUntil]
            if (blockedUntil != null && blockedUntil > now) {
                "Too many failed login attempts. Please try again later."
            } else {
                if (blockedUntil != null) {
                    AuthLoginThrottles.deleteWhere { AuthLoginThrottles.key eq rateLimitKey }
                }
                null
            }
        }
    }

    private fun recordLoginFailure(rateLimitKey: String) {
        transaction {
            val now = kotlinx.datetime.Clock.System.now()
            AuthLoginThrottles.insertIgnore {
                it[key] = rateLimitKey
                it[failedCount] = 0
                it[windowStartedAt] = now
                it[updatedAt] = now
            }
            val row = AuthLoginThrottles.selectAll()
                .where { AuthLoginThrottles.key eq rateLimitKey }
                .forUpdate()
                .first()
            val windowExpired = now.toEpochMilliseconds() - row[AuthLoginThrottles.windowStartedAt].toEpochMilliseconds() > rateLimitWindowMs
            val newCount = if (windowExpired) 1 else row[AuthLoginThrottles.failedCount] + 1
            AuthLoginThrottles.update({ AuthLoginThrottles.key eq rateLimitKey }) {
                it[failedCount] = newCount
                it[windowStartedAt] = if (windowExpired) now else row[AuthLoginThrottles.windowStartedAt]
                it[blockedUntil] = if (newCount >= maxFailedAttempts) nowPlus(blockDurationMs) else null
                it[updatedAt] = now
            }
        }
    }

    private fun clearLoginFailures(rateLimitKey: String) {
        transaction {
            AuthLoginThrottles.deleteWhere { AuthLoginThrottles.key eq rateLimitKey }
        }
    }
}
