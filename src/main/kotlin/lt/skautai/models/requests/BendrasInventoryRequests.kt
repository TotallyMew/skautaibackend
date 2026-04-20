package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreateBendrasInventoryRequestRequest(
    val itemId: String? = null,
    val itemDescription: String? = null,
    val quantity: Int = 1,
    val neededByDate: String? = null,
    val requestingUnitId: String? = null,
    val needsDraugininkasApproval: Boolean? = null,
    val notes: String? = null
)

@Serializable
data class DraugininkasReviewRequest(
    val action: String, // FORWARDED or REJECTED
    val rejectionReason: String? = null
)

@Serializable
data class TopLevelReviewRequest(
    val action: String, // APPROVED or REJECTED
    val rejectionReason: String? = null
)