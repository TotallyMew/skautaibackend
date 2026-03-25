package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreateItemRequest(
    val name: String,
    val description: String? = null,
    val category: String,
    val ownerType: String,
    val ownerId: String,
    val quantity: Int = 1,
    val locationId: String? = null,
    val responsibleUserId: String? = null,
    val photoUrl: String? = null,
    val purchaseDate: String? = null,
    val purchasePrice: Double? = null,
    val notes: String? = null
)

@Serializable
data class UpdateItemRequest(
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val condition: String? = null,
    val quantity: Int? = null,
    val locationId: String? = null,
    val responsibleUserId: String? = null,
    val photoUrl: String? = null,
    val purchaseDate: String? = null,
    val purchasePrice: Double? = null,
    val notes: String? = null,
    val status: String? = null
)