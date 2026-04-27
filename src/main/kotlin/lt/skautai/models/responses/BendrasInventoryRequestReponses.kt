package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class BendrasInventoryRequestItemResponse(
    val id: String,
    val itemId: String,
    val itemName: String,
    val quantity: Int
)

@Serializable
data class BendrasInventoryRequestResponse(
    val id: String,
    val tuntasId: String,
    val requestedByUserId: String,
    val requestedByUserName: String? = null,
    val itemId: String? = null,
    val itemName: String,
    val itemDescription: String? = null,
    val quantity: Int,
    val neededByDate: String? = null,
    val eventId: String? = null,
    val requestingUnitId: String? = null,
    val requestingUnitName: String? = null,
    val needsDraugininkasApproval: Boolean,
    val draugininkasStatus: String? = null,
    val draugininkasReviewedByUserId: String? = null,
    val draugininkasRejectionReason: String? = null,
    val topLevelStatus: String,
    val topLevelReviewedByUserId: String? = null,
    val topLevelRejectionReason: String? = null,
    val notes: String? = null,
    val items: List<BendrasInventoryRequestItemResponse> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class BendrasInventoryRequestListResponse(
    val requests: List<BendrasInventoryRequestResponse>,
    val total: Int
)
