package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class ItemCustomFieldRequest(
    val fieldName: String,
    val fieldValue: String? = null
)

@Serializable
data class CreateItemRequest(
    val name: String,
    val description: String? = null,
    val type: String,
    val category: String,
    val custodianId: String? = null,
    val origin: String = "UNIT_ACQUIRED",
    val quantity: Int = 1,
    val isConsumable: Boolean = false,
    val unitOfMeasure: String = "vnt.",
    val minimumQuantity: Int? = null,
    val condition: String = "GOOD",
    val locationId: String? = null,
    val temporaryStorageLabel: String? = null,
    val sourceSharedItemId: String? = null,
    val responsibleUserId: String? = null,
    val photoUrl: String? = null,
    val purchaseDate: String? = null,
    val purchasePrice: Double? = null,
    val notes: String? = null,
    val customFields: List<ItemCustomFieldRequest> = emptyList(),
    val duplicateHandling: String = "ASK",
    val duplicateTargetItemId: String? = null
)

@Serializable
data class UpdateItemRequest(
    val name: String? = null,
    val description: String? = null,
    val type: String? = null,
    val category: String? = null,
    val condition: String? = null,
    val quantity: Int? = null,
    val isConsumable: Boolean? = null,
    val unitOfMeasure: String? = null,
    val minimumQuantity: Int? = null,
    val custodianId: String? = null,
    val locationId: String? = null,
    val temporaryStorageLabel: String? = null,
    val sourceSharedItemId: String? = null,
    val responsibleUserId: String? = null,
    val photoUrl: String? = null,
    val purchaseDate: String? = null,
    val purchasePrice: Double? = null,
    val notes: String? = null,
    val customFields: List<ItemCustomFieldRequest>? = null,
    val status: String? = null,
    val clearCustodianId: Boolean = false,
    val clearLocationId: Boolean = false,
    val clearSourceSharedItemId: Boolean = false,
    val clearResponsibleUserId: Boolean = false,
    val clearMinimumQuantity: Boolean = false
)

@Serializable
data class TransferItemToUnitRequest(
    val targetUnitId: String,
    val quantity: Int,
    val notes: String? = null
)

@Serializable
data class ReturnItemToSharedRequest(
    val quantity: Int,
    val notes: String? = null
)

@Serializable
data class DirectItemLoanRequest(
    val issuedToUserId: String,
    val quantity: Int,
    val dueAt: String? = null,
    val notes: String? = null
)

@Serializable
data class ReturnDirectItemLoanRequest(
    val quantity: Int,
    val notes: String? = null
)

@Serializable
data class RestockItemRequest(
    val quantity: Int,
    val purchaseDate: String? = null,
    val purchasePrice: Double? = null,
    val notes: String? = null
)

@Serializable
data class ConsumeItemRequest(
    val quantity: Int,
    val notes: String? = null
)

@Serializable
data class WriteOffItemRequest(
    val reason: String
)

@Serializable
data class ReviewItemAdditionRequest(
    val decision: String,
    val rejectionReason: String? = null
)

@Serializable
data class CreateStorageAuditSessionRequest(
    val custodianId: String? = null,
    val type: String? = null,
    val category: String? = null,
    val sharedOnly: Boolean = false,
    val personalOwnerUserId: String? = null,
    val notes: String? = null
)

@Serializable
data class UpsertStorageAuditCheckRequest(
    val itemId: String,
    val result: String,
    val actualQuantity: Int? = null,
    val actualLocationId: String? = null,
    val actualLocationNote: String? = null,
    val conditionAtCheck: String? = null,
    val notes: String? = null
)

@Serializable
data class UpsertStorageAuditChecksRequest(
    val checks: List<UpsertStorageAuditCheckRequest>
)
