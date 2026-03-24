package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class InvitationResponse(
    val code: String,
    val roleName: String,
    val tuntasName: String,
    val expiresAt: String
)