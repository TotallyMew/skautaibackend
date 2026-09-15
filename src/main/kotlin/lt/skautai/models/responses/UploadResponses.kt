package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class UploadResponse(
    val url: String,
    val uploadId: String? = null
)
