package lt.skautai.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import lt.skautai.database.tables.*
import lt.skautai.models.requests.LoginRequest
import lt.skautai.models.requests.RegisterTuntininkasRequest
import lt.skautai.models.requests.RegisterWithInviteRequest
import lt.skautai.models.responses.TokenResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.util.*
import lt.skautai.models.responses.MessageResponse

class AuthService(private val environment: ApplicationEnvironment) {

    private val secret = environment.config.property("jwt.secret").getString()
    private val issuer = environment.config.property("jwt.issuer").getString()
    private val audience = environment.config.property("jwt.audience").getString()

    fun registerTuntininkas(request: RegisterTuntininkasRequest): Result<TokenResponse> {
        return transaction {
            // Check if email already exists
            val existingUser = Users.selectAll()
                .where { Users.email eq request.email }
                .firstOrNull()
            if (existingUser != null) {
                return@transaction Result.failure(Exception("Email already registered"))
            }

            val passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())

            // Create user
            val userId = Users.insert {
                it[name] = request.name
                it[surname] = request.surname
                it[email] = request.email
                it[this.passwordHash] = passwordHash
                it[phone] = request.phone
            } get Users.id

            // Create tuntas
            val tuntasId = Tuntai.insert {
                it[name] = request.tuntasName
                it[krastas] = request.tuntasKrastas
                it[contactEmail] = request.tuntasContactEmail
                it[status] = "PENDING"
            } get Tuntai.id

            // Create membership
            UserTuntasMemberships.insert {
                it[this.userId] = userId
                it[this.tuntasId] = tuntasId
            }

            // Find or create tuntininkas role
            val systemRoleNames = listOf(
                "Tuntininkas",
                "Inventorininkas",
                "Draugininkas",
                "Draugininko pavaduotojas",
                "Patyres skautas",
                "Skautas"
            )

            for (roleName in systemRoleNames) {
                Roles.insert {
                    it[this.tuntasId] = tuntasId
                    it[name] = roleName
                    it[isSystemRole] = true
                }
            }

            val tuntininkasRoleId = Roles.selectAll()
                .where { (Roles.name eq "Tuntininkas") and (Roles.tuntasId eq tuntasId) }
                .first()[Roles.id]

            // Assign role
            UserRoles.insert {
                it[this.userId] = userId
                it[roleId] = tuntininkasRoleId
                it[this.tuntasId] = tuntasId
            }

            val token = generateToken(userId.toString(), request.email, "user")
            Result.success(
                TokenResponse(
                    token = token,
                    userId = userId.toString(),
                    email = request.email,
                    name = request.name
                )
            )
        }
    }

    fun registerWithInvite(request: RegisterWithInviteRequest): Result<TokenResponse> {
        return transaction {
            // Check email
            val existingUser = Users.selectAll()
                .where { Users.email eq request.email }
                .firstOrNull()
            if (existingUser != null) {
                return@transaction Result.failure(Exception("Email already registered"))
            }

            // Find and validate invite
            val invite = Invitations.selectAll()
                .where { Invitations.code eq request.inviteCode }
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

            // Check tuntas is active
            val tuntas = Tuntai.selectAll()
                .where { Tuntai.id eq tuntasId }
                .firstOrNull()
            if (tuntas == null || tuntas[Tuntai.status] != "ACTIVE") {
                return@transaction Result.failure(Exception("Tuntas is not active"))
            }

            val passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())

            // Create user
            val userId = Users.insert {
                it[name] = request.name
                it[surname] = request.surname
                it[email] = request.email
                it[this.passwordHash] = passwordHash
                it[phone] = request.phone
            } get Users.id

            // Create membership
            UserTuntasMemberships.insert {
                it[this.userId] = userId
                it[this.tuntasId] = tuntasId
            }

            // Assign role
            UserRoles.insert {
                it[this.userId] = userId
                it[this.roleId] = roleId
                it[this.tuntasId] = tuntasId
                it[organizationalUnitId] = orgUnitId
                it[assignedByUserId] = invite[Invitations.createdByUserId]
            }

            // Mark invite as used
            Invitations.update({ Invitations.id eq invite[Invitations.id] }) {
                it[usedByUserId] = userId
                it[usedAt] = now
            }

            val token = generateToken(userId.toString(), request.email, "user")
            Result.success(
                TokenResponse(
                    token = token,
                    userId = userId.toString(),
                    email = request.email,
                    name = request.name
                )
            )
        }
    }

    fun login(request: LoginRequest): Result<TokenResponse> {
        return transaction {
            val user = Users.selectAll()
                .where { Users.email eq request.email }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Invalid email or password"))

            if (!BCrypt.checkpw(request.password, user[Users.passwordHash])) {
                return@transaction Result.failure(Exception("Invalid email or password"))
            }

            val token = generateToken(
                user[Users.id].toString(),
                user[Users.email],
                "user"
            )
            Result.success(
                TokenResponse(
                    token = token,
                    userId = user[Users.id].toString(),
                    email = user[Users.email],
                    name = user[Users.name]
                )
            )
        }
    }

    fun loginSuperAdmin(request: LoginRequest): Result<TokenResponse> {
        return transaction {
            val admin = SuperAdmins.selectAll()
                .where { SuperAdmins.email eq request.email }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Invalid email or password"))

            if (!BCrypt.checkpw(request.password, admin[SuperAdmins.passwordHash])) {
                return@transaction Result.failure(Exception("Invalid email or password"))
            }

            val token = generateToken(
                admin[SuperAdmins.id].toString(),
                admin[SuperAdmins.email],
                "super_admin"
            )
            Result.success(
                TokenResponse(
                    token = token,
                    userId = admin[SuperAdmins.id].toString(),
                    email = admin[SuperAdmins.email],
                    name = admin[SuperAdmins.name],
                    type = "super_admin"
                )
            )
        }
    }

    fun seedSuperAdmin(request: LoginRequest): Result<MessageResponse> {
        return transaction {
            val existingAdmin = SuperAdmins.selectAll().firstOrNull()
            if (existingAdmin != null) {
                return@transaction Result.failure(Exception("Super admin already exists"))
            }

            val passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())

            SuperAdmins.insert {
                it[name] = "Super Admin"
                it[email] = request.email
                it[this.passwordHash] = passwordHash
            }

            Result.success(MessageResponse("Super admin created successfully"))
        }
    }
    private fun generateToken(userId: String, email: String, type: String): String {
        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withClaim("type", type)
            .withExpiresAt(Date(System.currentTimeMillis() + 86400000)) // 24 hours
            .sign(Algorithm.HMAC256(secret))
    }
}