package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class ReservationResponse(
    val id: String,
    val itemId: String,
    val itemName: String,
    val tuntasId: String,
    val reservedByUserId: String,
    val approvedByUserId: String? = null,
    val requestingUnitId: String? = null,
    val eventId: String? = null,
    val quantity: Int,
    val startDate: String,
    val endDate: String,
    val status: String,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class ReservationListResponse(
    val reservations: List<ReservationResponse>,
    val total: Int
)