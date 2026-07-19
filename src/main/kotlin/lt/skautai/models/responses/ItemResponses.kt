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
data class ItemCapabilitiesResponse(
    val canEdit: Boolean = false,
    val canChangeStatus: Boolean = false,
    val canDelete: Boolean = false,
    val canRestock: Boolean = false,
    val canConsume: Boolean = false,
    val canLoan: Boolean = false,
    val canReturnLoan: Boolean = false,
    val canTransferToUnit: Boolean = false,
    val canReturnToShared: Boolean = false,
    val canReview: Boolean = false,
    val canWriteOff: Boolean = false
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
    val isConsumable: Boolean = false,
    val unitOfMeasure: String = "vnt.",
    val minimumQuantity: Int? = null,
    val isLowStock: Boolean = false,
    val locationId: String? = null,
    val locationName: String? = null,
    val locationPath: String? = null,
    val temporaryStorageLabel: String? = null,
    val kitId: String? = null,
    val kitName: String? = null,
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
    val submittedByUserId: String? = null,
    val submittedByUserName: String? = null,
    val targetScope: String? = null,
    val reviewedByUserId: String? = null,
    val rejectionReason: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val capabilities: ItemCapabilitiesResponse? = null
)

@Serializable
data class ItemListCapabilitiesResponse(
    val canCreate: Boolean = false,
    val canCreateSharedDirectly: Boolean = false,
    val canViewInactive: Boolean = false,
    val canViewPending: Boolean = false,
    val canReviewPending: Boolean = false,
    val canExport: Boolean = false,
    val canImport: Boolean = false,
    val canGenerateQrPdf: Boolean = false
)

@Serializable
data class ItemListResponse(
    val items: List<ItemResponse>,
    val total: Int,
    val limit: Int? = null,
    val offset: Int = 0,
    val hasMore: Boolean = false,
    val capabilities: ItemListCapabilitiesResponse = ItemListCapabilitiesResponse()
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
data class DirectItemLoanResponse(
    val id: String,
    val itemId: String,
    val itemName: String? = null,
    val issuedToUserId: String,
    val issuedToUserName: String? = null,
    val issuedByUserId: String,
    val issuedByUserName: String? = null,
    val quantity: Int,
    val returnedQuantity: Int,
    val outstandingQuantity: Int,
    val status: String,
    val issuedAt: String,
    val returnedAt: String? = null,
    val dueAt: String? = null,
    val notes: String? = null
)

@Serializable
data class DirectItemLoanListResponse(
    val loans: List<DirectItemLoanResponse>,
    val total: Int,
    val activeOutstandingQuantity: Int
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
data class ItemTransferResponse(
    val id: String,
    val itemId: String,
    val fromCustodianId: String? = null,
    val fromCustodianName: String? = null,
    val toCustodianId: String? = null,
    val toCustodianName: String? = null,
    val initiatedByUserId: String? = null,
    val initiatedByUserName: String? = null,
    val approvedByUserId: String? = null,
    val approvedByUserName: String? = null,
    val notes: String? = null,
    val status: String,
    val createdAt: String,
    val completedAt: String? = null
)

@Serializable
data class ItemTransferListResponse(
    val transfers: List<ItemTransferResponse>,
    val total: Int
)

@Serializable
data class ItemHistoryResponse(
    val id: String,
    val itemId: String,
    val eventType: String,
    val quantityChange: Int? = null,
    val performedByUserId: String? = null,
    val performedByUserName: String? = null,
    val requisitionId: String? = null,
    val notes: String? = null,
    val createdAt: String
)

@Serializable
data class ItemHistoryListResponse(
    val entries: List<ItemHistoryResponse>,
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

@Serializable
data class ItemCheckResponse(
    val id: String,
    val sessionId: String,
    val itemId: String? = null,
    val eventInventoryItemId: String? = null,
    val custodyId: String? = null,
    val itemName: String? = null,
    val qrToken: String? = null,
    val result: String,
    val quantity: Int,
    val expectedQuantity: Int,
    val actualQuantity: Int,
    val quantityDifference: Int,
    val quantityChangeDirection: String,
    val actualLocationId: String? = null,
    val actualLocationPath: String? = null,
    val actualLocationNote: String? = null,
    val conditionAtCheck: String? = null,
    val checkedByUserId: String,
    val checkedByUserName: String? = null,
    val checkedAt: String,
    val notes: String? = null
)

@Serializable
data class ItemCheckSummaryResponse(
    val total: Int,
    val checked: Int,
    val unchecked: Int,
    val found: Int,
    val missing: Int,
    val misplaced: Int,
    val damaged: Int,
    val consumed: Int,
    val returned: Int,
    val matched: Int,
    val decreased: Int,
    val increased: Int,
    val expectedQuantityTotal: Int,
    val actualQuantityTotal: Int,
    val shortageQuantityTotal: Int,
    val overageQuantityTotal: Int
)

@Serializable
data class ItemCheckSessionResponse(
    val id: String,
    val tuntasId: String,
    val contextType: String,
    val status: String,
    val eventId: String? = null,
    val scopeCustodianId: String? = null,
    val scopeCustodianName: String? = null,
    val scopeType: String? = null,
    val scopeCategory: String? = null,
    val scopeSharedOnly: Boolean,
    val scopePersonalOwnerUserId: String? = null,
    val startedByUserId: String,
    val startedByUserName: String? = null,
    val completedByUserId: String? = null,
    val completedByUserName: String? = null,
    val notes: String? = null,
    val createdAt: String,
    val completedAt: String? = null,
    val summary: ItemCheckSummaryResponse,
    val checks: List<ItemCheckResponse>
)

@Serializable
data class ItemCheckSessionListResponse(
    val sessions: List<ItemCheckSessionResponse>,
    val total: Int
)
