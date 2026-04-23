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
