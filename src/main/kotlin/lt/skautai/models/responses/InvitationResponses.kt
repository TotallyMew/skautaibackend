package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class InvitationResponse(
    val code: String,
    val tuntasId: String,
    val roleName: String,
    val tuntasName: String,
    val expiresAt: String,
    val organizationalUnitId: String? = null,
    val organizationalUnitName: String? = null
)

@Serializable
data class InvitationUnitOptionResponse(
    val id: String,
    val name: String,
    val type: String
)

@Serializable
data class InvitationRoleOptionResponse(
    val role: RoleResponse,
    val organizationalUnits: List<InvitationUnitOptionResponse>,
    val canInviteWithoutOrganizationalUnit: Boolean
)

@Serializable
data class InvitationOptionsResponse(
    val roles: List<InvitationRoleOptionResponse>
)
