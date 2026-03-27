package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreateReservationRequest(
    val itemId: String,
    val quantity: Int = 1,
    val startDate: String,
    val endDate: String,
    val eventId: String? = null,
    val notes: String? = null
)

@Serializable
data class UpdateReservationStatusRequest(
    val status: String,
    val notes: String? = null
)