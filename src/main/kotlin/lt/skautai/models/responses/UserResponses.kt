package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class MyProfileResponse(
    val userId: String,
    val name: String,
    val surname: String,
    val email: String,
    val phone: String? = null,
    val createdAt: String,
    val updatedAt: String
)
