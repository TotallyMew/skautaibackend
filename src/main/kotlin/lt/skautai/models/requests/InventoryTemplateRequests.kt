package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class InventoryTemplateItemRequest(
    val itemId: String? = null,
    val itemName: String,
    val quantity: Int = 1,
    val category: String? = null,
    val notes: String? = null
)

@Serializable
data class CreateInventoryTemplateRequest(
    val name: String,
    val eventType: String? = null,
    val items: List<InventoryTemplateItemRequest> = emptyList()
)

@Serializable
data class UpdateInventoryTemplateRequest(
    val name: String? = null,
    val eventType: String? = null,
    val items: List<InventoryTemplateItemRequest>? = null
)

@Serializable
data class ApplyInventoryTemplateRequest(
    val templateId: String
)
