package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class EventRoleResponse(
    val id: String,
    val userId: String,
    val userName: String? = null,
    val role: String,
    val targetGroup: String? = null,
    val assignedByUserId: String? = null,
    val assignedAt: String
)

@Serializable
data class StovyklaDetailsResponse(
    val id: String,
    val registrationDeadline: String? = null,
    val expectedParticipants: Int? = null,
    val actualParticipants: Int? = null
)

@Serializable
data class EventResponse(
    val id: String,
    val tuntasId: String,
    val name: String,
    val type: String,
    val startDate: String,
    val endDate: String,
    val locationId: String? = null,
    val organizationalUnitId: String? = null,
    val createdByUserId: String? = null,
    val status: String,
    val notes: String? = null,
    val createdAt: String,
    val eventRoles: List<EventRoleResponse>,
    val stovyklaDetails: StovyklaDetailsResponse? = null,
    val inventorySummary: EventInventorySummaryResponse? = null
)

@Serializable
data class EventListResponse(
    val events: List<EventResponse>,
    val total: Int
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
    val responsibleUserId: String? = null,
    val responsibleUserName: String? = null,
    val createdByUserId: String? = null,
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
data class EventPurchaseResponse(
    val id: String,
    val eventId: String,
    val purchasedByUserId: String? = null,
    val purchasedByName: String? = null,
    val status: String,
    val purchaseDate: String? = null,
    val totalAmount: Double? = null,
    val invoiceFileUrl: String? = null,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val items: List<EventPurchaseItemResponse>
)

@Serializable
data class EventPurchaseListResponse(
    val purchases: List<EventPurchaseResponse>,
    val total: Int
)
