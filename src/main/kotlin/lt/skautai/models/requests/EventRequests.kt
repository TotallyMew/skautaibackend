package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreateEventRequest(
    val name: String,
    val type: String,
    val startDate: String,
    val endDate: String,
    val locationId: String? = null,
    val organizationalUnitId: String? = null,
    val notes: String? = null,
    val registrationDeadline: String? = null,
    val expectedParticipants: Int? = null
)

@Serializable
data class UpdateEventRequest(
    val name: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val locationId: String? = null,
    val organizationalUnitId: String? = null,
    val notes: String? = null,
    val status: String? = null
)

@Serializable
data class AssignEventRoleRequest(
    val userId: String,
    val role: String,
    val targetGroup: String? = null
)

@Serializable
data class UpdateStovyklaDetailsRequest(
    val registrationDeadline: String? = null,
    val expectedParticipants: Int? = null,
    val actualParticipants: Int? = null
)

@Serializable
data class CreatePastovykleRequest(
    val name: String,
    val responsibleUserId: String? = null,
    val ageGroup: String? = null,
    val notes: String? = null
)

@Serializable
data class UpdatePastovykleRequest(
    val name: String? = null,
    val responsibleUserId: String? = null,
    val ageGroup: String? = null,
    val notes: String? = null
)

@Serializable
data class AssignPastovykleInventoryRequest(
    val itemId: String,
    val quantity: Int,
    val recipientUserId: String? = null,
    val recipientType: String? = null,
    val notes: String? = null
)

@Serializable
data class UpdatePastovykleInventoryRequest(
    val quantityReturned: Int? = null,
    val returnedAt: String? = null,
    val notes: String? = null
)