package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    val token: String,
    val userId: String,
    val email: String,
    val name: String,
    val type: String = "user",
    val tuntai: List<TuntasInfo> = emptyList()
)

@Serializable
data class MessageResponse(
    val message: String
)

@Serializable
data class ErrorResponse(
    val error: String
)