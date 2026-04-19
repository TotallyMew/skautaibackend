package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class ItemResponse(
    val id: String,
    val tuntasId: String,
    val custodianId: String? = null,
    val custodianName: String? = null,
    val origin: String,
    val name: String,
    val description: String? = null,
    val category: String,
    val condition: String,
    val quantity: Int,
    val locationId: String? = null,
    val responsibleUserId: String? = null,
    val photoUrl: String? = null,
    val purchaseDate: String? = null,
    val purchasePrice: Double? = null,
    val notes: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class ItemListResponse(
    val items: List<ItemResponse>,
    val total: Int
)