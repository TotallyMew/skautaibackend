package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class LocationResponse(
    val id: String,
    val tuntasId: String,
    val name: String,
    val visibility: String,
    val parentLocationId: String? = null,
    val ownerUserId: String? = null,
    val ownerUnitId: String? = null,
    val ownerUnitName: String? = null,
    val fullPath: String,
    val hasChildren: Boolean,
    val isLeafSelectable: Boolean,
    val isEditable: Boolean,
    val address: String? = null,
    val description: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val createdAt: String
)

@Serializable
data class LocationListResponse(
    val locations: List<LocationResponse>,
    val total: Int
)
