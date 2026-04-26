package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreateReservationItemRequest(
    val itemId: String,
    val quantity: Int = 1
)

@Serializable
data class CreateReservationRequest(
    val title: String = "Rezervacija",
    val items: List<CreateReservationItemRequest> = emptyList(),
    val itemId: String? = null,
    val quantity: Int = 1,
    val startDate: String,
    val endDate: String,
    val requestingUnitId: String? = null,
    val eventId: String? = null,
    val pickupLocationId: String? = null,
    val returnLocationId: String? = null,
    val notes: String? = null
)

@Serializable
data class UpdateReservationStatusRequest(
    val status: String,
    val notes: String? = null
)

@Serializable
data class ReviewReservationRequest(
    val status: String,
    val notes: String? = null
)

@Serializable
data class ReservationMovementItemRequest(
    val itemId: String,
    val quantity: Int
)

@Serializable
data class ReservationMovementRequest(
    val items: List<ReservationMovementItemRequest>,
    val locationId: String? = null,
    val notes: String? = null
)

@Serializable
data class UpdateReservationPickupRequest(
    val pickupAt: String? = null,
    val pickupLocationId: String? = null,
    val response: String? = null
)

@Serializable
data class UpdateReservationReturnTimeRequest(
    val returnAt: String? = null,
    val returnLocationId: String? = null,
    val response: String? = null
)
