package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreateLocationRequest(
    val name: String,
    val visibility: String = "PUBLIC",
    val parentLocationId: String? = null,
    val ownerUnitId: String? = null,
    val address: String? = null,
    val description: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

@Serializable
data class UpdateLocationRequest(
    val name: String? = null,
    val visibility: String? = null,
    val parentLocationId: String? = null,
    val ownerUnitId: String? = null,
    val address: String? = null,
    val description: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)
