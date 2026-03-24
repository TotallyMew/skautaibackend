package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreateInvitationRequest(
    val roleId: String,
    val organizationalUnitId: String? = null,
    val expiresInHours: Int = 48
)