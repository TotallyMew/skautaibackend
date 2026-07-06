package lt.skautai.services

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import lt.skautai.database.tables.AccountDeletionRequests
import lt.skautai.database.tables.AuthRefreshSessions
import lt.skautai.database.tables.Users
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

class AccountDeletionService(
    private val emailService: EmailService = ResendEmailService()
) {
    companion object {
        private const val tokenLifetimeMs = 60 * 60 * 1000L
        private const val requestWindowMs = 15 * 60 * 1000L
        private const val maxRequestsPerWindow = 3
    }

    fun requestFromApp(userId: UUID, password: String): Result<Unit> {
        if (password.isBlank()) return Result.failure(Exception("Įveskite dabartinį slaptažodį."))
        val user = transaction {
            Users.selectAll()
                .where { (Users.id eq userId) and Users.deletedAt.isNull() }
                .firstOrNull()
        } ?: return Result.failure(Exception("Vartotojas nerastas."))
        if (!BCrypt.checkpw(password, user[Users.passwordHash])) {
            return Result.failure(Exception("Neteisingas dabartinis slaptažodis."))
        }
        return createRequest(userId, user[Users.email], user[Users.name], "APP")
    }

    fun requestFromWeb(email: String): Result<Unit> {
        val normalizedEmail = email.trim().lowercase(Locale.ROOT)
        val user = transaction {
            Users.selectAll()
                .where { (Users.email eq normalizedEmail) and Users.deletedAt.isNull() }
                .firstOrNull()
        } ?: return Result.success(Unit)
        return createRequest(user[Users.id], user[Users.email], user[Users.name], "WEB")
    }

    fun tokenStatus(rawToken: String): TokenStatus = transaction {
        val request = findRequest(rawToken) ?: return@transaction TokenStatus.INVALID
        when {
            request[AccountDeletionRequests.confirmedAt] != null -> TokenStatus.USED
            request[AccountDeletionRequests.expiresAt] <= Clock.System.now() -> TokenStatus.EXPIRED
            else -> TokenStatus.VALID
        }
    }

    fun confirm(rawToken: String): Result<Unit> = transaction {
        val request = AccountDeletionRequests.selectAll()
            .where { AccountDeletionRequests.tokenHash eq hashToken(rawToken) }
            .forUpdate()
            .firstOrNull()
            ?: return@transaction Result.failure(Exception("Nuoroda netinkama."))
        val now = Clock.System.now()
        if (request[AccountDeletionRequests.confirmedAt] != null) {
            return@transaction Result.failure(Exception("Ši nuoroda jau panaudota."))
        }
        if (request[AccountDeletionRequests.expiresAt] <= now) {
            return@transaction Result.failure(Exception("Nuorodos galiojimas baigėsi."))
        }

        val userId = request[AccountDeletionRequests.userId]
        val userExists = Users.selectAll()
            .where { (Users.id eq userId) and Users.deletedAt.isNull() }
            .firstOrNull() != null
        if (!userExists) {
            return@transaction Result.failure(Exception("Paskyra jau ištrinta."))
        }

        // Remove live access and personal relationships. Historical operational rows remain linked
        // to the anonymized user so shared inventory and financial audit trails stay consistent.
        execForUser("DELETE FROM devices WHERE user_id = ?", userId)
        execForUser("DELETE FROM notifications WHERE user_id = ?", userId)
        execForUser("DELETE FROM sync_operations WHERE user_id = ?", userId)
        execForUser("DELETE FROM item_assignments WHERE assigned_to_user_id = ?", userId)
        execForUser("DELETE FROM event_roles WHERE user_id = ?", userId)
        execForUser("DELETE FROM pastovykle_members WHERE user_id = ?", userId)
        execForUser("DELETE FROM user_ranks WHERE user_id = ?", userId)
        execForUser("DELETE FROM unit_assignments WHERE user_id = ?", userId)
        execForUser("DELETE FROM user_leadership_roles WHERE user_id = ?", userId)
        execForUser("DELETE FROM user_tuntas_memberships WHERE user_id = ?", userId)

        execForUser("UPDATE items SET responsible_user_id = NULL WHERE responsible_user_id = ?", userId)
        execForUser("UPDATE locations SET owner_user_id = NULL WHERE owner_user_id = ?", userId)
        execForUser("UPDATE inventory_kits SET responsible_user_id = NULL WHERE responsible_user_id = ?", userId)
        execForUser("UPDATE pastovykles SET responsible_user_id = NULL WHERE responsible_user_id = ?", userId)
        execForUser("UPDATE event_inventory_items SET responsible_user_id = NULL WHERE responsible_user_id = ?", userId)
        execForUser("UPDATE event_inventory_requests SET responsible_user_id = NULL WHERE responsible_user_id = ?", userId)
        execForUser("UPDATE event_inventory_custody SET holder_user_id = NULL WHERE holder_user_id = ?", userId)
        execForUser("UPDATE item_check_sessions SET scope_personal_owner_user_id = NULL WHERE scope_personal_owner_user_id = ?", userId)

        AuthRefreshSessions.update({
            (AuthRefreshSessions.subjectId eq userId) and
                (AuthRefreshSessions.subjectType eq "user") and
                AuthRefreshSessions.revokedAt.isNull()
        }) {
            it[revokedAt] = now
            it[lastUsedAt] = now
        }

        val anonymousEmail = "deleted-$userId@deleted.invalid"
        Users.update({ Users.id eq userId }) {
            it[name] = "Ištrintas"
            it[surname] = "naudotojas"
            it[email] = anonymousEmail
            it[phone] = null
            it[passwordHash] = BCrypt.hashpw(randomToken(), BCrypt.gensalt())
            it[updatedAt] = now
            it[deletedAt] = now
        }
        AccountDeletionRequests.update({
            (AccountDeletionRequests.userId eq userId) and
                AccountDeletionRequests.confirmedAt.isNull()
        }) {
            it[confirmedAt] = now
        }
        Result.success(Unit)
    }

    private fun createRequest(userId: UUID, email: String, name: String, via: String): Result<Unit> {
        val now = Clock.System.now()
        val windowStart = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - requestWindowMs)
        val throttled = transaction {
            AccountDeletionRequests.selectAll().where {
                (AccountDeletionRequests.userId eq userId) and
                    (AccountDeletionRequests.createdAt greaterEq windowStart)
            }.count() >= maxRequestsPerWindow
        }
        if (throttled) return Result.success(Unit)

        val rawToken = randomToken()
        val requestId = UUID.randomUUID()
        transaction {
            AccountDeletionRequests.update({
                (AccountDeletionRequests.userId eq userId) and
                    AccountDeletionRequests.confirmedAt.isNull()
            }) {
                it[confirmedAt] = now
            }
            AccountDeletionRequests.insert {
                it[id] = requestId
                it[AccountDeletionRequests.userId] = userId
                it[tokenHash] = hashToken(rawToken)
                it[requestedVia] = via
                it[expiresAt] = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + tokenLifetimeMs)
                it[createdAt] = now
            }
        }

        val baseUrl = setting("ACCOUNT_DELETION_PUBLIC_BASE_URL")
            ?: setting("PASSWORD_RESET_PUBLIC_BASE_URL")
            ?: return Result.failure(Exception("Paskyros ištrynimo paslauga nesukonfigūruota."))
        val confirmationUrl = "${baseUrl.trimEnd('/')}/account-deletion/confirm?token=${
            URLEncoder.encode(rawToken, StandardCharsets.UTF_8)
        }"
        return emailService.sendAccountDeletionConfirmation(email, name, confirmationUrl)
            .onFailure {
                transaction { AccountDeletionRequests.deleteWhere { id eq requestId } }
            }
    }

    private fun findRequest(rawToken: String) = AccountDeletionRequests.selectAll()
        .where { AccountDeletionRequests.tokenHash eq hashToken(rawToken) }
        .firstOrNull()

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun setting(name: String): String? =
        (System.getenv(name) ?: System.getProperty(name))?.trim()?.takeIf(String::isNotBlank)
}

private fun Transaction.execForUser(sql: String, userId: UUID) {
    exec(sql, listOf(UUIDColumnType() to userId))
}

enum class TokenStatus { VALID, INVALID, EXPIRED, USED }
