package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class LocationResponse(
    val id: String,
    val tuntasId: String,
    val name: String,
    val address: String? = null,
    val description: String? = null,
    val createdAt: String
)

@Serializable
data class LocationListResponse(
    val locations: List<LocationResponse>,
    val total: Int
)