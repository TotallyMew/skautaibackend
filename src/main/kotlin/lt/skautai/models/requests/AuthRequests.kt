package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class RegisterTuntininkasRequest(
    val name: String,
    val surname: String,
    val email: String,
    val password: String,
    val phone: String? = null,
    val tuntasName: String,
    val tuntasKrastas: String? = null,
    val tuntasContactEmail: String? = null
)

@Serializable
data class RegisterWithInviteRequest(
    val name: String,
    val surname: String,
    val email: String,
    val password: String,
    val phone: String? = null,
    val inviteCode: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
)

@Serializable
data class ForgotPasswordRequest(
    val email: String
)

@Serializable
data class ResetPasswordRequest(
    val token: String,
    val newPassword: String
)
