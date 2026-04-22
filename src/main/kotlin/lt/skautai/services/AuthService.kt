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
import java.util.*
import lt.skautai.database.tables.UnitAssignments
class AuthService(private val environment: ApplicationEnvironment) {

    private val secret = environment.config.property("jwt.secret").getString()
    private val issuer = environment.config.property("jwt.issuer").getString()
    private val audience = environment.config.property("jwt.audience").getString()

    // role name -> role_type
    private val systemRoles = mapOf(
        "Tuntininkas" to "LEADERSHIP",
        "Tuntininko pavaduotojas" to "LEADERSHIP",
        "Inventorininkas" to "LEADERSHIP",
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
        "Vilkas" to "RANK",
        "Skautas" to "RANK",
        "Patyres skautas" to "RANK",
        "Vyr. skautas kandidatas" to "RANK",
        "Vyr. skautas" to "RANK",
        "Vadovas" to "RANK"
    )

    fun registerTuntininkas(request: RegisterTuntininkasRequest): Result<TokenResponse> {
        return transaction {
            val existingUser = Users.selectAll()
                .where { Users.email eq request.email }
                .firstOrNull()
            if (existingUser != null) {
                return@transaction Result.failure(Exception("Email already registered"))
            }

            val passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())

            val userId = Users.insert {
                it[name] = request.name
                it[surname] = request.surname
                it[email] = request.email
                it[this.passwordHash] = passwordHash
                it[phone] = request.phone
            } get Users.id

            val tuntasId = Tuntai.insert {
                it[name] = request.tuntasName
                it[krastas] = request.tuntasKrastas
                it[contactEmail] = request.tuntasContactEmail
                it[status] = "PENDING"
            } get Tuntai.id

            UserTuntasMemberships.insert {
                it[this.userId] = userId
                it[this.tuntasId] = tuntasId
            }

            // Seed all system roles with correct role_type
            for ((roleName, roleType) in systemRoles) {
                Roles.insert {
                    it[this.tuntasId] = tuntasId
                    it[name] = roleName
                    it[isSystemRole] = true
                    it[this.roleType] = roleType
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
            val existingUser = Users.selectAll()
                .where { Users.email eq request.email }
                .firstOrNull()
            if (existingUser != null) {
                return@transaction Result.failure(Exception("Email already registered"))
            }

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
                it[name] = request.name
                it[surname] = request.surname
                it[email] = request.email
                it[this.passwordHash] = passwordHash
                it[phone] = request.phone
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

            val token = generateToken(userId.toString(), request.email, "user")
            val tuntai = getActiveTuntaiForUser(userId)
            Result.success(
                TokenResponse(
                    token = token,
                    userId = userId.toString(),
                    email = request.email,
                    name = request.name,
                    tuntai = tuntai
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
            val tuntai = getActiveTuntaiForUser(user[Users.id])
            Result.success(
                TokenResponse(
                    token = token,
                    userId = user[Users.id].toString(),
                    email = user[Users.email],
                    name = user[Users.name],
                    tuntai = tuntai
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
                    contactEmail = it[Tuntai.contactEmail] ?: ""
                )
            }
    }
    private fun generateToken(userId: String, email: String, type: String): String {
        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withClaim("type", type)
            .withExpiresAt(Date(System.currentTimeMillis() + 86400000))
            .sign(Algorithm.HMAC256(secret))
    }
}
