package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class ReservationItemResponse(
    val itemId: String,
    val itemName: String,
    val quantity: Int
)

@Serializable
data class ReservationResponse(
    val id: String,
    val title: String,
    val tuntasId: String,
    val reservedByUserId: String,
    val approvedByUserId: String? = null,
    val requestingUnitId: String? = null,
    val eventId: String? = null,
    val totalItems: Int,
    val totalQuantity: Int,
    val startDate: String,
    val endDate: String,
    val status: String,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val items: List<ReservationItemResponse>
)

@Serializable
data class ReservationListResponse(
    val reservations: List<ReservationResponse>,
    val total: Int
)

@Serializable
data class ReservationAvailabilityItemResponse(
    val itemId: String,
    val totalQuantity: Int,
    val reservedQuantity: Int,
    val availableQuantity: Int
)

@Serializable
data class ReservationAvailabilityResponse(
    val startDate: String,
    val endDate: String,
    val items: List<ReservationAvailabilityItemResponse>
)
