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