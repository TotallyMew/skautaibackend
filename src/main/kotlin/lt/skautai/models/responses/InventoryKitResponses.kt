package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class InventoryKitItemResponse(
    val id: String,
    val itemId: String,
    val itemName: String,
    val itemCondition: String,
    val itemStatus: String,
    val availableQuantity: Int,
    val quantity: Int,
    val locationId: String? = null,
    val locationName: String? = null,
    val locationPath: String? = null,
    val notes: String? = null
)

@Serializable
data class InventoryKitResponse(
    val id: String,
    val tuntasId: String,
    val custodianId: String? = null,
    val custodianName: String? = null,
    val name: String,
    val description: String? = null,
    val locationId: String? = null,
    val locationName: String? = null,
    val locationPath: String? = null,
    val temporaryStorageLabel: String? = null,
    val responsibleUserId: String? = null,
    val responsibleUserName: String? = null,
    val createdByUserId: String? = null,
    val createdByUserName: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val items: List<InventoryKitItemResponse> = emptyList()
)

@Serializable
data class InventoryKitListResponse(
    val kits: List<InventoryKitResponse>,
    val total: Int
)
