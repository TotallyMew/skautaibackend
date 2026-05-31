package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class InventoryTemplateItemResponse(
    val id: String,
    val templateId: String,
    val itemId: String? = null,
    val itemName: String,
    val quantity: Int,
    val category: String? = null,
    val notes: String? = null
)

@Serializable
data class InventoryTemplateResponse(
    val id: String,
    val tuntasId: String,
    val name: String,
    val eventType: String? = null,
    val createdByUserId: String? = null,
    val createdByUserName: String? = null,
    val createdAt: String,
    val items: List<InventoryTemplateItemResponse> = emptyList()
)

@Serializable
data class InventoryTemplateListResponse(
    val templates: List<InventoryTemplateResponse>,
    val total: Int
)

@Serializable
data class AppliedTemplateReservedItemResponse(
    val templateItemName: String,
    val itemId: String,
    val itemName: String,
    val eventInventoryItemId: String,
    val reservationGroupId: String,
    val quantity: Int
)

@Serializable
data class AppliedTemplateSourceResponse(
    val templateItemName: String,
    val eventInventoryItemId: String,
    val sourceId: String,
    val itemId: String? = null,
    val itemName: String? = null,
    val reservedQuantity: Int,
    val plannedQuantity: Int,
    val pickupSummary: String? = null,
    val sourceStatus: String
)

@Serializable
data class AppliedTemplateShortageResponse(
    val templateItemName: String,
    val eventInventoryItemId: String,
    val shortageQuantity: Int
)

@Serializable
data class AppliedTemplatePurchaseItemResponse(
    val templateItemName: String,
    val eventInventoryItemId: String,
    val purchaseId: String,
    val purchaseItemId: String,
    val quantity: Int
)

@Serializable
data class AppliedInventoryTemplateResponse(
    val reserved: List<AppliedTemplateReservedItemResponse>,
    val toPurchase: List<AppliedTemplatePurchaseItemResponse>,
    val sources: List<AppliedTemplateSourceResponse> = emptyList(),
    val shortages: List<AppliedTemplateShortageResponse> = emptyList(),
    val reservedTotal: Int,
    val toPurchaseTotal: Int
)
