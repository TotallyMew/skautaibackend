package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreateRequisitionItemRequest(
    val itemName: String,
    val itemDescription: String? = null,
    val quantity: Int = 1,
    val notes: String? = null,
    val requestType: String = "NEW_ITEM",
    val existingItemId: String? = null
)

@Serializable
data class CreateRequisitionRequest(
    val requestingUnitId: String? = null,
    val neededByDate: String? = null,
    val notes: String? = null,
    val items: List<CreateRequisitionItemRequest>
)

@Serializable
data class RequisitionUnitReviewRequest(
    val action: String, // APPROVED, FORWARDED, REJECTED
    val rejectionReason: String? = null
)

@Serializable
data class RequisitionTopLevelReviewRequest(
    val action: String, // APPROVED, REJECTED
    val rejectionReason: String? = null
)
