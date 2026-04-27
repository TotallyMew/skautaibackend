package lt.skautai.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import lt.skautai.database.tables.*
import lt.skautai.models.requests.LoginRequest
import lt.skautai.models.requests.RegisterTuntininkasRequest
import lt.skautai.models.requests.RegisterWithInviteRequest
import lt.skautai.models.responses.MessageResponse
import lt.skautai.models.responses.TokenResponse
import lt.skautai.models.responses.TuntasInfo
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.util.concurrent.ConcurrentHashMap
import java.util.*
import lt.skautai.database.tables.UnitAssignments
class AuthService(private val environment: ApplicationEnvironment) {
    companion object {
        private const val accessTokenLifetimeMs = 8 * 60 * 60 * 1000L
        private const val refreshTokenLifetimeMs = 30L * 24 * 60 * 60 * 1000
        private const val maxFailedAttempts = 5
        private const val rateLimitWindowMs = 15 * 60 * 1000L
        private const val blockDurationMs = 15 * 60 * 1000L
        private val failedLoginAttempts = ConcurrentHashMap<String, MutableList<Long>>()
        private val blockedUntil = ConcurrentHashMap<String, Long>()
    }

    private val secret = environment.config.property("jwt.secret").getString()
    private val issuer = environment.config.property("jwt.issuer").getString()
    private val audience = environment.config.property("jwt.audience").getString()
    private val emailRegex = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
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
            val tuntasName = request.tuntasName.trim()
            val tuntasKrastas = request.tuntasKrastas?.trim().orEmpty()

            validateRequired(name, "Name")?.let { return@transaction Result.failure(Exception(it)) }
            validateRequired(surname, "Surname")?.let { return@transaction Result.failure(Exception(it)) }
            validateEmail(email)?.let { return@transaction Result.failure(Exception(it)) }
            validatePassword(request.password)?.let { return@transaction Result.failure(Exception(it)) }
            validateRequired(tuntasName, "Tuntas name")?.let { return@transaction Result.failure(Exception(it)) }
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
                it[Users.phone] = request.phone
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
            val refreshToken = generateRefreshToken(userId.toString(), email, "user")
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
            val inviteCode = request.inviteCode.trim()

            validateRequired(name, "Name")?.let { return@transaction Result.failure(Exception(it)) }
            validateRequired(surname, "Surname")?.let { return@transaction Result.failure(Exception(it)) }
            validateEmail(email)?.let { return@transaction Result.failure(Exception(it)) }
            validatePassword(request.password)?.let { return@transaction Result.failure(Exception(it)) }
            validateRequired(inviteCode, "Invite code")?.let { return@transaction Result.failure(Exception(it)) }

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
                it[Users.phone] = request.phone
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
            val refreshToken = generateRefreshToken(userId.toString(), email, "user")
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
        val rateLimitKey = "user:$email"
        loginRateLimitError(rateLimitKey)?.let { return Result.failure(Exception(it)) }

        return transaction {
            val user = Users.selectAll()
                .where { Users.email eq email }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Invalid email or password"))

            if (!BCrypt.checkpw(request.password, user[Users.passwordHash])) {
                return@transaction Result.failure(Exception("Invalid email or password"))
            }

            val token = generateAccessToken(
                user[Users.id].toString(),
                user[Users.email],
                "user"
            )
            val refreshToken = generateRefreshToken(
                user[Users.id].toString(),
                user[Users.email],
                "user"
            )
            val tuntai = getActiveTuntaiForUser(user[Users.id])
            Result.success(
                TokenResponse(
                    token = token,
                    refreshToken = refreshToken,
                    userId = user[Users.id].toString(),
                    email = user[Users.email],
                    name = user[Users.name],
                    tuntai = tuntai
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

    fun loginSuperAdmin(request: LoginRequest): Result<TokenResponse> {
        val email = normalizeEmail(request.email)
        val rateLimitKey = "super_admin:$email"
        loginRateLimitError(rateLimitKey)?.let { return Result.failure(Exception(it)) }

        return transaction {
            val admin = SuperAdmins.selectAll()
                .where { SuperAdmins.email eq email }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Invalid email or password"))

            if (!BCrypt.checkpw(request.password, admin[SuperAdmins.passwordHash])) {
                return@transaction Result.failure(Exception("Invalid email or password"))
            }

            val token = generateAccessToken(
                admin[SuperAdmins.id].toString(),
                admin[SuperAdmins.email],
                "super_admin"
            )
            val refreshToken = generateRefreshToken(
                admin[SuperAdmins.id].toString(),
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

        return transaction {
            when (type) {
                "user" -> {
                    val user = Users.selectAll()
                        .where { Users.id eq userUuid }
                        .firstOrNull()
                        ?: return@transaction Result.failure(Exception("User not found"))

                    val tuntai = getActiveTuntaiForUser(userUuid)
                    Result.success(
                        TokenResponse(
                            token = generateAccessToken(userId, email, type),
                            refreshToken = generateRefreshToken(userId, email, type),
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

                    Result.success(
                        TokenResponse(
                            token = generateAccessToken(userId, email, type),
                            refreshToken = generateRefreshToken(userId, email, type),
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

    private fun validateRequired(value: String, label: String): String? {
        return if (value.isBlank()) "$label is required" else null
    }

    private fun validateEmail(email: String): String? {
        return when {
            email.isBlank() -> "Email is required"
            !emailRegex.matches(email) -> "Invalid email format"
            else -> null
        }
    }

    private fun validatePassword(password: String): String? {
        return when {
            password.isBlank() -> "Password is required"
            password.length < 8 -> "Password must be at least 8 characters"
            password.any { it.isWhitespace() } -> "Password cannot contain spaces"
            !password.any { it.isLetter() } -> "Password must contain a letter"
            !password.any { it.isDigit() } -> "Password must contain a number"
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

    private fun generateRefreshToken(userId: String, email: String, type: String): String {
        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withClaim("type", type)
            .withClaim("tokenUse", "refresh")
            .withExpiresAt(Date(System.currentTimeMillis() + refreshTokenLifetimeMs))
            .sign(Algorithm.HMAC256(secret))
    }

    private fun loginRateLimitError(rateLimitKey: String): String? {
        val now = System.currentTimeMillis()
        val blockedAt = blockedUntil[rateLimitKey]
        if (blockedAt != null && blockedAt > now) {
            return "Too many failed login attempts. Please try again later."
        }
        if (blockedAt != null && blockedAt <= now) {
            blockedUntil.remove(rateLimitKey)
        }
        return null
    }

    private fun recordLoginFailure(rateLimitKey: String) {
        val now = System.currentTimeMillis()
        val attempts = failedLoginAttempts.computeIfAbsent(rateLimitKey) { mutableListOf() }
        synchronized(attempts) {
            attempts.removeAll { now - it > rateLimitWindowMs }
            attempts.add(now)
            if (attempts.size >= maxFailedAttempts) {
                blockedUntil[rateLimitKey] = now + blockDurationMs
            }
        }
    }

    private fun clearLoginFailures(rateLimitKey: String) {
        failedLoginAttempts.remove(rateLimitKey)
        blockedUntil.remove(rateLimitKey)
    }
}
