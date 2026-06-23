package lt.skautai.models.responses

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ReservationItemResponse(
    val itemId: String,
    val itemName: String,
    val quantity: Int,
    val custodianId: String? = null,
    val custodianName: String? = null,
    val remainingAfterReservation: Int? = null,
    @EncodeDefault
    val issuedQuantity: Int = 0,
    @EncodeDefault
    val returnedQuantity: Int = 0,
    @EncodeDefault
    val markedReturnedQuantity: Int = 0,
    @EncodeDefault
    val remainingToIssue: Int = quantity,
    @EncodeDefault
    val remainingToReturn: Int = 0,
    @EncodeDefault
    val remainingToMarkReturned: Int = 0,
    @EncodeDefault
    val remainingToReceive: Int = 0
)

@Serializable
data class ReservationResponse(
    val id: String,
    val title: String,
    val tuntasId: String,
    val reservedByUserId: String,
    val reservedByName: String? = null,
    val approvedByUserId: String? = null,
    val requestingUnitId: String? = null,
    val requestingUnitName: String? = null,
    val eventId: String? = null,
    val totalItems: Int,
    val totalQuantity: Int,
    val startDate: String,
    val endDate: String,
    val status: String,
    val unitReviewStatus: String = "NOT_REQUIRED",
    val unitReviewedByUserId: String? = null,
    val unitReviewedAt: String? = null,
    val topLevelReviewStatus: String = "NOT_REQUIRED",
    val topLevelReviewedByUserId: String? = null,
    val topLevelReviewedAt: String? = null,
    val pickupAt: String? = null,
    val pickupLocationId: String? = null,
    val pickupLocationPath: String? = null,
    val pickupProposalStatus: String = "NONE",
    val pickupProposedAt: String? = null,
    val pickupProposedByUserId: String? = null,
    val pickupRespondedAt: String? = null,
    val pickupRespondedByUserId: String? = null,
    val returnAt: String? = null,
    val returnLocationId: String? = null,
    val returnLocationPath: String? = null,
    val returnProposalStatus: String = "NONE",
    val returnProposedAt: String? = null,
    val returnProposedByUserId: String? = null,
    val returnRespondedAt: String? = null,
    val returnRespondedByUserId: String? = null,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val items: List<ReservationItemResponse>
)

@Serializable
data class ReservationListResponse(
    val reservations: List<ReservationResponse>,
    val total: Int,
    val limit: Int? = null,
    val offset: Int = 0,
    val hasMore: Boolean = false
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

@Serializable
data class ReservationMovementResponse(
    val id: String,
    val reservationId: String,
    val itemId: String,
    val itemName: String? = null,
    val locationId: String? = null,
    val locationPath: String? = null,
    val type: String,
    val quantity: Int,
    val performedByUserId: String,
    val notes: String? = null,
    val createdAt: String
)

@Serializable
data class ReservationMovementListResponse(
    val movements: List<ReservationMovementResponse>,
    val total: Int
)
