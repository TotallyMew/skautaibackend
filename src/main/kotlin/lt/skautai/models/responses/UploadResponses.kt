package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class UploadResponse(
    val url: String
)
