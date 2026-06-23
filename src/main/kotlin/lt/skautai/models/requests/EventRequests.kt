package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreateEventRequest(
    val name: String,
    val type: String,
    val customTypeLabel: String? = null,
    val startDate: String,
    val endDate: String,
    val locationId: String? = null,
    val organizationalUnitId: String? = null,
    val notes: String? = null
)

@Serializable
data class UpdateEventRequest(
    val name: String? = null,
    val type: String? = null,
    val customTypeLabel: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val locationId: String? = null,
    val organizationalUnitId: String? = null,
    val notes: String? = null,
    val status: String? = null
)

@Serializable
data class UpdateEventFinanceBudgetRequest(
    val inventoryBudgetAmount: Double? = null
)

@Serializable
data class AssignEventRoleRequest(
    val userId: String,
    val role: String,
    val targetGroup: String? = null,
    val pastovykleId: String? = null
)

@Serializable
data class CreatePastovykleRequest(
    val name: String,
    val responsibleUserId: String? = null,
    val ageGroup: String? = null,
    val notes: String? = null
)

@Serializable
data class UpdatePastovykleRequest(
    val name: String? = null,
    val responsibleUserId: String? = null,
    val clearResponsibleUser: Boolean = false,
    val ageGroup: String? = null,
    val notes: String? = null
)

@Serializable
data class AddPastovykleMemberRequest(
    val userId: String
)

@Serializable
data class AssignPastovykleLeaderRequest(
    val userId: String
)

@Serializable
data class AssignPastovykleInventoryRequest(
    val itemId: String,
    val quantity: Int,
    val recipientUserId: String? = null,
    val recipientType: String? = null,
    val notes: String? = null
)

@Serializable
data class UpdatePastovykleInventoryRequest(
    val quantityReturned: Int? = null,
    val returnedAt: String? = null,
    val notes: String? = null
)

@Serializable
data class CreateEventInventoryBucketRequest(
    val name: String,
    val type: String,
    val pastovykleId: String? = null,
    val locationId: String? = null,
    val notes: String? = null
)

@Serializable
data class UpdateEventInventoryBucketRequest(
    val name: String? = null,
    val type: String? = null,
    val pastovykleId: String? = null,
    val locationId: String? = null,
    val notes: String? = null
)

@Serializable
data class CreateEventInventoryItemRequest(
    val itemId: String? = null,
    val name: String,
    val plannedQuantity: Int,
    val bucketId: String? = null,
    val responsibleUserId: String? = null,
    val notes: String? = null
)

@Serializable
data class CreateEventInventoryItemsBulkRequest(
    val items: List<CreateEventInventoryItemRequest>
)

@Serializable
data class UpdateEventInventoryItemRequest(
    val name: String? = null,
    val plannedQuantity: Int? = null,
    val bucketId: String? = null,
    val responsibleUserId: String? = null,
    val notes: String? = null
)

@Serializable
data class CreateEventInventorySourceRequest(
    val itemId: String? = null,
    val plannedQuantity: Int,
    val notes: String? = null
)

@Serializable
data class UpdateEventInventorySourceRequest(
    val plannedQuantity: Int? = null,
    val notes: String? = null,
    val sourceStatus: String? = null
)

@Serializable
data class CreateEventInventoryAllocationRequest(
    val eventInventoryItemId: String,
    val bucketId: String,
    val quantity: Int,
    val notes: String? = null
)

@Serializable
data class UpdateEventInventoryAllocationRequest(
    val quantity: Int? = null,
    val notes: String? = null
)

@Serializable
data class CreateEventPurchaseItemRequest(
    val eventInventoryItemId: String,
    val purchasedQuantity: Int,
    val unitPrice: Double? = null,
    val notes: String? = null
)

@Serializable
data class CreateEventPurchaseRequest(
    val purchaseDate: String? = null,
    val notes: String? = null,
    val items: List<CreateEventPurchaseItemRequest>
)

@Serializable
data class UpdateEventPurchaseRequest(
    val status: String? = null,
    val purchaseDate: String? = null,
    val totalAmount: Double? = null,
    val invoiceFileUrl: String? = null,
    val notes: String? = null
)

@Serializable
data class AttachEventPurchaseInvoiceRequest(
    val invoiceFileUrl: String
)

@Serializable
data class CreateEventExtraCostRequest(
    val category: String,
    val label: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val unitPrice: Double? = null,
    val totalAmount: Double? = null,
    val notes: String? = null
)

@Serializable
data class UpdateEventExtraCostRequest(
    val category: String? = null,
    val label: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val unitPrice: Double? = null,
    val totalAmount: Double? = null,
    val notes: String? = null
)

@Serializable
data class CreateEventInventoryMovementRequest(
    val eventInventoryItemId: String,
    val movementType: String,
    val quantity: Int,
    val pastovykleId: String? = null,
    val toUserId: String? = null,
    val fromCustodyId: String? = null,
    val requestId: String? = null,
    val notes: String? = null
)

@Serializable
data class CreateEventInventoryTransferRequest(
    val sourceCustodyId: String,
    val quantity: Int,
    val notes: String? = null
)

@Serializable
data class RespondEventInventoryTransferRequest(
    val approve: Boolean,
    val notes: String? = null
)

@Serializable
data class CreatePastovykleInventoryRequestRequest(
    val eventInventoryItemId: String,
    val quantity: Int,
    val notes: String? = null,
    val provider: String = "UKVEDYS",
    val dueAt: String? = null,
    val responsibleUserId: String? = null
)

@Serializable
data class UpdateEventInventoryRequestRequest(
    val provider: String? = null,
    val dueAt: String? = null,
    val clearDueAt: Boolean = false,
    val responsibleUserId: String? = null,
    val clearResponsibleUserId: Boolean = false,
    val notes: String? = null
)

@Serializable
data class CreateEventInventoryRequestRequest(
    val eventInventoryItemId: String,
    val quantity: Int,
    val notes: String? = null
)

@Serializable
data class FulfillPastovykleInventoryRequestRequest(
    val quantity: Int? = null,
    val notes: String? = null
)

@Serializable
data class MarkPastovykleInventoryRequestSelfProvidedRequest(
    val notes: String? = null
)

@Serializable
data class AssignUnitInventoryToPastovykleRequest(
    val itemId: String,
    val quantity: Int,
    val notes: String? = null
)

@Serializable
data class ReconcileEventReturnLineRequest(
    val custodyId: String,
    val decision: String,
    val quantity: Int,
    val returnToMode: String? = null,
    val returnLocationId: String? = null,
    val returnLocationNote: String? = null,
    val notes: String? = null
)

@Serializable
data class ReconcileEventReturnsRequest(
    val returns: List<ReconcileEventReturnLineRequest>
)

@Serializable
data class ReconcileEventPurchaseLineRequest(
    val purchaseItemId: String,
    val decision: String,
    val quantity: Int,
    val existingItemId: String? = null,
    val name: String? = null,
    val notes: String? = null
)

@Serializable
data class ReconcileEventPurchasesRequest(
    val purchases: List<ReconcileEventPurchaseLineRequest>
)

@Serializable
data class CreateEventPackingContainerRequest(
    val name: String,
    val type: String = "BOX",
    val notes: String? = null
)

@Serializable
data class UpdateEventPackingLineRequest(
    val status: String? = null,
    val containerId: String? = null,
    val clearContainer: Boolean = false,
    val notes: String? = null
)
