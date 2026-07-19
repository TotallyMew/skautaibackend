package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class RequisitionItemResponse(
    val id: String,
    val itemId: String? = null,
    val requestType: String = "NEW_ITEM",
    val existingItemId: String? = null,
    val itemName: String,
    val itemDescription: String? = null,
    val quantityRequested: Int,
    val quantityApproved: Int? = null,
    val rejectionReason: String? = null,
    val notes: String? = null
)

@Serializable
data class RequisitionCapabilitiesResponse(
    val canReviewUnit: Boolean = false,
    val canReviewTopLevel: Boolean = false,
    val canCancel: Boolean = false,
    val canMarkPurchased: Boolean = false,
    val canAddToInventory: Boolean = false
)

@Serializable
data class RequisitionResponse(
    val id: String,
    val tuntasId: String,
    val createdByUserId: String,
    val requestingUnitId: String? = null,
    val requestingUnitName: String? = null,
    val status: String,
    val unitReviewStatus: String,
    val unitReviewedByUserId: String? = null,
    val unitReviewedAt: String? = null,
    val topLevelReviewStatus: String,
    val topLevelReviewedByUserId: String? = null,
    val topLevelReviewedAt: String? = null,
    val purchasedAt: String? = null,
    val addedToInventoryAt: String? = null,
    val reviewLevel: String,
    val lastAction: String,
    val neededByDate: String? = null,
    val notes: String? = null,
    val items: List<RequisitionItemResponse>,
    val createdAt: String,
    val updatedAt: String,
    val capabilities: RequisitionCapabilitiesResponse? = null
)

@Serializable
data class RequisitionListResponse(
    val requests: List<RequisitionResponse>,
    val total: Int
)
