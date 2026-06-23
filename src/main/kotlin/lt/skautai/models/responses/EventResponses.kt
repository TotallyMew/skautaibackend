package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class EventRoleResponse(
    val id: String,
    val userId: String,
    val userName: String? = null,
    val role: String,
    val targetGroup: String? = null,
    val pastovykleId: String? = null,
    val assignedByUserId: String? = null,
    val assignedAt: String
)

@Serializable
data class EventResponse(
    val id: String,
    val tuntasId: String,
    val name: String,
    val type: String,
    val customTypeLabel: String? = null,
    val startDate: String,
    val endDate: String,
    val locationId: String? = null,
    val organizationalUnitId: String? = null,
    val createdByUserId: String? = null,
    val status: String,
    val inventoryBudgetAmount: Double? = null,
    val notes: String? = null,
    val createdAt: String,
    val eventRoles: List<EventRoleResponse>,
    val inventorySummary: EventInventorySummaryResponse? = null,
    val financeSummary: EventFinanceSummaryResponse? = null
)

@Serializable
data class EventListResponse(
    val events: List<EventResponse>,
    val total: Int,
    val limit: Int? = null,
    val offset: Int = 0,
    val hasMore: Boolean = false
)

@Serializable
data class PastovykleResponse(
    val id: String,
    val eventId: String,
    val name: String,
    val responsibleUserId: String? = null,
    val ageGroup: String? = null,
    val notes: String? = null
)

@Serializable
data class PastovykleListResponse(
    val pastovykles: List<PastovykleResponse>,
    val total: Int
)

@Serializable
data class PastovykleMemberResponse(
    val id: String,
    val pastovykleId: String,
    val userId: String,
    val userName: String,
    val status: String,
    val addedAt: String,
    val addedByUserId: String
)

@Serializable
data class PastovykleMemberListResponse(
    val members: List<PastovykleMemberResponse>,
    val total: Int
)

@Serializable
data class PastovykleInventoryResponse(
    val id: String,
    val pastovykleId: String,
    val itemId: String,
    val itemName: String,
    val distributedByUserId: String? = null,
    val recipientUserId: String? = null,
    val recipientType: String? = null,
    val quantityAssigned: Int,
    val quantityReturned: Int,
    val assignedAt: String,
    val returnedAt: String? = null,
    val notes: String? = null
)

@Serializable
data class PastovykleInventoryListResponse(
    val inventory: List<PastovykleInventoryResponse>,
    val total: Int
)

@Serializable
data class EventInventoryBucketResponse(
    val id: String,
    val eventId: String,
    val name: String,
    val type: String,
    val pastovykleId: String? = null,
    val pastovykleName: String? = null,
    val locationId: String? = null,
    val locationPath: String? = null,
    val notes: String? = null
)

@Serializable
data class EventInventoryItemResponse(
    val id: String,
    val eventId: String,
    val itemId: String? = null,
    val bucketId: String? = null,
    val bucketName: String? = null,
    val reservationGroupId: String? = null,
    val name: String,
    val plannedQuantity: Int,
    val availableQuantity: Int,
    val shortageQuantity: Int,
    val allocatedQuantity: Int,
    val unallocatedQuantity: Int,
    val needsPurchase: Boolean,
    val notes: String? = null,
    val sourceCustodianName: String? = null,
    val sourceLocationPath: String? = null,
    val sourceTemporaryStorageLabel: String? = null,
    val sourceResponsibleUserName: String? = null,
    val sourcePickupSummary: String? = null,
    val sources: List<EventInventorySourceResponse> = emptyList(),
    val responsibleUserId: String? = null,
    val responsibleUserName: String? = null,
    val createdByUserId: String? = null,
    val createdAt: String
)

@Serializable
data class EventInventorySourceResponse(
    val id: String,
    val eventInventoryItemId: String,
    val itemId: String? = null,
    val reservationGroupId: String? = null,
    val plannedQuantity: Int,
    val reservedQuantity: Int,
    val pickupCustodianName: String? = null,
    val pickupLocationPath: String? = null,
    val pickupTemporaryStorageLabel: String? = null,
    val pickupResponsibleUserName: String? = null,
    val pickupSummary: String? = null,
    val sourceStatus: String,
    val notes: String? = null,
    val createdAt: String
)

@Serializable
data class EventInventoryAllocationResponse(
    val id: String,
    val eventInventoryItemId: String,
    val bucketId: String,
    val bucketName: String,
    val quantity: Int,
    val notes: String? = null
)

@Serializable
data class EventInventoryPlanResponse(
    val buckets: List<EventInventoryBucketResponse>,
    val items: List<EventInventoryItemResponse>,
    val allocations: List<EventInventoryAllocationResponse>
)

@Serializable
data class EventInventoryItemListResponse(
    val items: List<EventInventoryItemResponse>,
    val total: Int
)

@Serializable
data class EventInventorySummaryResponse(
    val totalPlannedQuantity: Int,
    val totalAvailableQuantity: Int,
    val totalShortageQuantity: Int,
    val totalAllocatedQuantity: Int,
    val itemsNeedingPurchase: Int
)

@Serializable
data class EventPurchaseItemResponse(
    val id: String,
    val purchaseId: String,
    val eventInventoryItemId: String,
    val itemName: String,
    val purchasedQuantity: Int,
    val unitPrice: Double? = null,
    val lineTotal: Double? = null,
    val addedToInventory: Boolean,
    val addedToInventoryItemId: String? = null,
    val notes: String? = null
)

@Serializable
data class EventPurchaseInvoiceResponse(
    val id: String,
    val purchaseId: String,
    val fileUrl: String,
    val createdAt: String
)

@Serializable
data class EventPurchaseResponse(
    val id: String,
    val eventId: String,
    val purchasedByUserId: String? = null,
    val purchasedByName: String? = null,
    val status: String,
    val purchaseDate: String? = null,
    val totalAmount: Double? = null,
    val invoiceFileUrl: String? = null,
    val invoices: List<EventPurchaseInvoiceResponse> = emptyList(),
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val items: List<EventPurchaseItemResponse>
)

@Serializable
data class EventPurchaseListResponse(
    val purchases: List<EventPurchaseResponse>,
    val total: Int,
    val limit: Int? = null,
    val offset: Int = 0,
    val hasMore: Boolean = false
)

@Serializable
data class EventExtraCostResponse(
    val id: String,
    val eventId: String,
    val category: String,
    val label: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val unitPrice: Double? = null,
    val totalAmount: Double,
    val notes: String? = null,
    val createdByUserId: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class EventFinanceSummaryResponse(
    val inventoryBudgetAmount: Double? = null,
    val purchaseTotal: Double,
    val extraCostTotal: Double,
    val spentTotal: Double,
    val remainingAmount: Double? = null,
    val overBudget: Boolean
)

@Serializable
data class EventFinanceResponse(
    val eventId: String,
    val summary: EventFinanceSummaryResponse,
    val extraCosts: List<EventExtraCostResponse>
)

@Serializable
data class EventInventoryCustodyResponse(
    val id: String,
    val eventInventoryItemId: String,
    val itemName: String,
    val pastovykleId: String? = null,
    val pastovykleName: String? = null,
    val holderUserId: String? = null,
    val holderUserName: String? = null,
    val quantity: Int,
    val returnedQuantity: Int,
    val remainingQuantity: Int,
    val status: String,
    val createdByUserId: String,
    val createdByUserName: String? = null,
    val createdAt: String,
    val closedAt: String? = null,
    val notes: String? = null
)

@Serializable
data class EventInventoryMovementResponse(
    val id: String,
    val eventId: String,
    val eventInventoryItemId: String,
    val itemName: String,
    val custodyId: String? = null,
    val movementType: String,
    val quantity: Int,
    val fromPastovykleId: String? = null,
    val fromPastovykleName: String? = null,
    val toPastovykleId: String? = null,
    val toPastovykleName: String? = null,
    val fromUserId: String? = null,
    val fromUserName: String? = null,
    val toUserId: String? = null,
    val toUserName: String? = null,
    val performedByUserId: String,
    val performedByUserName: String? = null,
    val notes: String? = null,
    val createdAt: String
)

@Serializable
data class EventInventoryCustodyListResponse(
    val custody: List<EventInventoryCustodyResponse>,
    val total: Int
)

@Serializable
data class EventInventoryMovementListResponse(
    val movements: List<EventInventoryMovementResponse>,
    val total: Int
)

@Serializable
data class EventInventoryTransferRequestResponse(
    val id: String,
    val eventId: String,
    val sourceCustodyId: String,
    val eventInventoryItemId: String,
    val itemName: String,
    val requestedByUserId: String,
    val requestedByUserName: String? = null,
    val requestedFromUserId: String,
    val requestedFromUserName: String? = null,
    val quantity: Int,
    val status: String,
    val notes: String? = null,
    val createdAt: String,
    val respondedAt: String? = null,
    val respondedByUserId: String? = null,
    val movementId: String? = null
)

@Serializable
data class EventInventoryTransferRequestListResponse(
    val requests: List<EventInventoryTransferRequestResponse>,
    val total: Int
)

@Serializable
data class EventInventoryRequestResponse(
    val id: String,
    val eventId: String,
    val eventInventoryItemId: String,
    val itemId: String? = null,
    val itemName: String,
    val pastovykleId: String? = null,
    val pastovykleName: String? = null,
    val targetGroup: String? = null,
    val requestedByUserId: String,
    val requestedByName: String? = null,
    val quantity: Int,
    val status: String,
    val notes: String? = null,
    val createdAt: String,
    val reviewedAt: String? = null,
    val reviewedByUserId: String? = null,
    val reviewedByUserName: String? = null,
    val fulfilledAt: String? = null,
    val resolvedByUserId: String? = null,
    val resolvedByUserName: String? = null,
    val provider: String = "UKVEDYS",
    val dueAt: String? = null,
    val responsibleUserId: String? = null,
    val responsibleUserName: String? = null,
    val providerHistory: List<EventInventoryRequestProviderHistoryResponse> = emptyList()
)

@Serializable
data class EventInventoryRequestProviderHistoryResponse(
    val id: String,
    val fromProvider: String? = null,
    val toProvider: String,
    val changedByUserId: String,
    val changedByUserName: String? = null,
    val notes: String? = null,
    val createdAt: String
)

@Serializable
data class EventInventoryRequestListResponse(
    val requests: List<EventInventoryRequestResponse>,
    val total: Int
)

@Serializable
data class EventInventoryConflictResponse(
    val itemId: String,
    val itemName: String,
    val availableQuantity: Int,
    val requestedQuantity: Int,
    val overlappingEvents: List<String>
)

@Serializable
data class EventInventoryReadinessResponse(
    val readinessPercent: Int,
    val totalQuantity: Int,
    val completedQuantity: Int,
    val openQuantity: Int,
    val overdueCount: Int,
    val unassignedCount: Int,
    val conflicts: List<EventInventoryConflictResponse>
)

@Serializable
data class EventReconciliationReturnLineResponse(
    val custodyId: String,
    val eventInventoryItemId: String,
    val itemId: String? = null,
    val itemName: String,
    val pastovykleId: String? = null,
    val pastovykleName: String? = null,
    val holderUserId: String? = null,
    val holderUserName: String? = null,
    val quantity: Int,
    val returnedQuantity: Int,
    val remainingQuantity: Int,
    val reconciledQuantity: Int = returnedQuantity,
    val pendingQuantity: Int = remainingQuantity,
    val status: String,
    val isReturned: Boolean,
    val currentHolderSummary: String? = null,
    val sourcePickupSummary: String? = null,
    val returnDecision: String? = null,
    val returnedToSummary: String? = null,
    val returnCondition: String? = null,
    val auditLog: List<EventReconciliationAuditResponse> = emptyList(),
    val notes: String? = null
)

@Serializable
data class EventReconciliationAuditResponse(
    val id: String,
    val quantity: Int,
    val expectedQuantity: Int,
    val actualQuantity: Int,
    val result: String,
    val actualLocationId: String? = null,
    val actualLocationNote: String? = null,
    val conditionAtCheck: String? = null,
    val checkedByUserId: String,
    val checkedAt: String,
    val notes: String? = null
)

@Serializable
data class EventReconciliationPurchaseLineResponse(
    val purchaseId: String,
    val purchaseItemId: String,
    val eventInventoryItemId: String,
    val itemId: String? = null,
    val itemName: String,
    val purchasedQuantity: Int,
    val status: String,
    val invoiceFileUrl: String? = null,
    val notes: String? = null
)

@Serializable
data class EventPurchaseReconciliationCandidateResponse(
    val itemId: String,
    val name: String,
    val quantity: Int,
    val custodianId: String? = null,
    val custodianName: String? = null,
    val recommended: Boolean = false
)

@Serializable
data class EventPurchaseReconciliationCandidateListResponse(
    val candidates: List<EventPurchaseReconciliationCandidateResponse>,
    val total: Int
)

@Serializable
data class EventReconciliationResponse(
    val eventId: String,
    val sessionId: String? = null,
    val status: String,
    val openReturns: List<EventReconciliationReturnLineResponse>,
    val returnedToEventStorage: List<EventReconciliationReturnLineResponse>,
    val unresolvedPurchases: List<EventReconciliationPurchaseLineResponse>,
    val canComplete: Boolean
)

@Serializable
data class EventPackingContainerResponse(
    val id: String,
    val eventId: String,
    val name: String,
    val type: String,
    val status: String,
    val sortOrder: Int,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class EventPackingLineResponse(
    val id: String,
    val eventId: String,
    val eventInventoryItemId: String,
    val allocationId: String? = null,
    val containerId: String? = null,
    val containerName: String? = null,
    val bucketId: String? = null,
    val bucketName: String? = null,
    val itemId: String? = null,
    val itemName: String,
    val requiredQuantity: Int,
    val status: String,
    val sourceSummary: String? = null,
    val notes: String? = null,
    val checkedByUserId: String? = null,
    val checkedByUserName: String? = null,
    val checkedAt: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class EventPackingSummaryResponse(
    val totalLines: Int,
    val doneLines: Int,
    val totalQuantity: Int,
    val doneQuantity: Int,
    val progressPercent: Int
)

@Serializable
data class EventPackingListResponse(
    val eventId: String,
    val containers: List<EventPackingContainerResponse>,
    val lines: List<EventPackingLineResponse>,
    val summary: EventPackingSummaryResponse
)
