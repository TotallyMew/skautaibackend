package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class InventoryKitItemRequest(
    val itemId: String,
    val quantity: Int = 1,
    val notes: String? = null
)

@Serializable
data class CreateInventoryKitRequest(
    val name: String,
    val description: String? = null,
    val custodianId: String? = null,
    val locationId: String? = null,
    val temporaryStorageLabel: String? = null,
    val responsibleUserId: String? = null,
    val items: List<InventoryKitItemRequest> = emptyList()
)

@Serializable
data class UpdateInventoryKitRequest(
    val name: String? = null,
    val description: String? = null,
    val custodianId: String? = null,
    val locationId: String? = null,
    val temporaryStorageLabel: String? = null,
    val responsibleUserId: String? = null,
    val status: String? = null,
    val clearLocationId: Boolean = false,
    val clearResponsibleUserId: Boolean = false,
    val items: List<InventoryKitItemRequest>? = null
)
