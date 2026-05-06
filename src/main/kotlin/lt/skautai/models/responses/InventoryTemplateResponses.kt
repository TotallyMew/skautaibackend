package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class InventoryTemplateItemResponse(
    val id: String,
    val templateId: String,
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
