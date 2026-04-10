package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class BendrasInventoryRequestResponse(
    val id: String,
    val tuntasId: String,
    val requestedByUserId: String,
    val itemId: String,
    val itemName: String,
    val quantity: Int,
    val eventId: String? = null,
    val draugoveId: String? = null,
    val draugoveName: String? = null,
    val needsDraugininkasApproval: Boolean,
    val draugininkasStatus: String? = null,
    val draugininkasReviewedByUserId: String? = null,
    val draugininkasRejectionReason: String? = null,
    val topLevelStatus: String,
    val topLevelReviewedByUserId: String? = null,
    val topLevelRejectionReason: String? = null,
    val startDate: String,
    val endDate: String,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class BendrasInventoryRequestListResponse(
    val requests: List<BendrasInventoryRequestResponse>,
    val total: Int
)