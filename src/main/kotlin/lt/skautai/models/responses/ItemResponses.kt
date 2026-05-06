package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class ItemDistributionResponse(
    val holderName: String,
    val quantity: Int
)

@Serializable
data class ItemCustomFieldResponse(
    val id: String,
    val fieldName: String,
    val fieldValue: String? = null
)

@Serializable
data class ItemResponse(
    val id: String,
    val qrToken: String,
    val tuntasId: String,
    val custodianId: String? = null,
    val custodianName: String? = null,
    val origin: String,
    val name: String,
    val description: String? = null,
    val type: String,
    val category: String,
    val condition: String,
    val quantity: Int,
    val locationId: String? = null,
    val locationName: String? = null,
    val locationPath: String? = null,
    val temporaryStorageLabel: String? = null,
    val sourceSharedItemId: String? = null,
    val responsibleUserId: String? = null,
    val responsibleUserName: String? = null,
    val createdByUserId: String? = null,
    val createdByUserName: String? = null,
    val photoUrl: String? = null,
    val purchaseDate: String? = null,
    val purchasePrice: Double? = null,
    val notes: String? = null,
    val customFields: List<ItemCustomFieldResponse> = emptyList(),
    val quantityBreakdown: List<ItemDistributionResponse> = emptyList(),
    val totalQuantityAcrossCustodians: Int = quantity,
    val status: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class ItemListResponse(
    val items: List<ItemResponse>,
    val total: Int
)

@Serializable
data class ItemAssignmentResponse(
    val id: String,
    val itemId: String,
    val assignedToUserId: String,
    val assignedToUserName: String? = null,
    val assignedByUserId: String? = null,
    val assignedByUserName: String? = null,
    val assignedAt: String,
    val unassignedAt: String? = null,
    val reason: String? = null,
    val notes: String? = null
)

@Serializable
data class ItemAssignmentListResponse(
    val assignments: List<ItemAssignmentResponse>,
    val total: Int
)

@Serializable
data class ItemConditionLogResponse(
    val id: String,
    val itemId: String,
    val previousCondition: String? = null,
    val newCondition: String,
    val reportedByUserId: String? = null,
    val reportedByUserName: String? = null,
    val reportedAt: String,
    val notes: String? = null
)

@Serializable
data class ItemConditionLogListResponse(
    val entries: List<ItemConditionLogResponse>,
    val total: Int
)

@Serializable
data class ItemQrResolveResponse(
    val itemId: String
)

@Serializable
data class DuplicateItemConflictResponse(
    val error: String,
    val duplicateItem: ItemResponse
)
