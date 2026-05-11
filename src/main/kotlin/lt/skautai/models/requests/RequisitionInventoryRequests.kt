package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class RequisitionMarkPurchasedRequest(
    val notes: String? = null
)

@Serializable
data class AddRequisitionItemToInventoryRequest(
    val requisitionItemId: String,
    val action: String,
    val existingItemId: String? = null,
    val custodianId: String? = null,
    val type: String = "COLLECTIVE",
    val category: String = "TOOLS",
    val condition: String = "GOOD",
    val purchaseDate: String? = null,
    val purchasePrice: Double? = null,
    val notes: String? = null
)

@Serializable
data class AddRequisitionToInventoryRequest(
    val items: List<AddRequisitionItemToInventoryRequest>
)
