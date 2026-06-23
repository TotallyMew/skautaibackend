package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class UpdateMyProfileRequest(
    val name: String,
    val surname: String,
    val email: String,
    val phone: String? = null
)

@Serializable
data class ChangeMyPasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

@Serializable
data class RequestAccountDeletionRequest(
    val password: String
)

@Serializable
data class PublicAccountDeletionRequest(
    val email: String
)
