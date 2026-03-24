package lt.skautai.services

import lt.skautai.database.tables.*
import lt.skautai.models.requests.CreateInvitationRequest
import lt.skautai.models.responses.InvitationResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.datetime.Clock
import java.util.*
import kotlin.time.Duration.Companion.hours

class InvitationService {

    fun createInvitation(
        userId: UUID,
        tuntasId: UUID,
        request: CreateInvitationRequest
    ): Result<InvitationResponse> {
        return transaction {
            // Verify tuntas is active
            val tuntas = Tuntai.selectAll()
                .where { Tuntai.id eq tuntasId }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Tuntas not found"))

            if (tuntas[Tuntai.status] != "ACTIVE") {
                return@transaction Result.failure(Exception("Tuntas is not active"))
            }

            // Verify role exists and belongs to this tuntas
            val roleUUID = try {
                UUID.fromString(request.roleId)
            } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid role ID"))
            }

            val role = Roles.selectAll()
                .where { (Roles.id eq roleUUID) and (Roles.tuntasId eq tuntasId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Role not found in this tuntas"))

            // Verify organizational unit if provided
            val orgUnitUUID = request.organizationalUnitId?.let {
                try {
                    UUID.fromString(it)
                } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid organizational unit ID"))
                }
            }

            if (orgUnitUUID != null) {
                val unit = OrganizationalUnits.selectAll()
                    .where { (OrganizationalUnits.id eq orgUnitUUID) and (OrganizationalUnits.tuntasId eq tuntasId) }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Organizational unit not found in this tuntas"))
            }

            val code = generateCode()
            val expiresAt = Clock.System.now().plus(request.expiresInHours.hours)

            Invitations.insert {
                it[this.tuntasId] = tuntasId
                it[this.code] = code
                it[this.roleId] = roleUUID
                it[organizationalUnitId] = orgUnitUUID
                it[createdByUserId] = userId
                it[this.expiresAt] = expiresAt
            }

            Result.success(
                InvitationResponse(
                    code = code,
                    roleName = role[Roles.name],
                    tuntasName = tuntas[Tuntai.name],
                    expiresAt = expiresAt.toString()
                )
            )
        }
    }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..8).map { chars.random() }.joinToString("")
    }
}