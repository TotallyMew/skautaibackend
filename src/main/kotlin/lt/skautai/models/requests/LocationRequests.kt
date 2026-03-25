package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreateLocationRequest(
    val name: String,
    val address: String? = null,
    val description: String? = null
)

@Serializable
data class UpdateLocationRequest(
    val name: String? = null,
    val address: String? = null,
    val description: String? = null
)