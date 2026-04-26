package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreateEventRequest(
    val name: String,
    val type: String,
    val startDate: String,
    val endDate: String,
    val locationId: String? = null,
    val organizationalUnitId: String? = null,
    val notes: String? = null
)

@Serializable
data class UpdateEventRequest(
    val name: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val locationId: String? = null,
    val organizationalUnitId: String? = null,
    val notes: String? = null,
    val status: String? = null
)

@Serializable
data class AssignEventRoleRequest(
    val userId: String,
    val role: String,
    val targetGroup: String? = null
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
    val ageGroup: String? = null,
    val notes: String? = null
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
data class CreatePastovykleInventoryRequestRequest(
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
