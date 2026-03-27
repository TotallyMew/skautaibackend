package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class EventRoleResponse(
    val id: String,
    val userId: String,
    val role: String,
    val targetGroup: String? = null,
    val assignedByUserId: String? = null,
    val assignedAt: String
)

@Serializable
data class StovyklaDetailsResponse(
    val id: String,
    val registrationDeadline: String? = null,
    val expectedParticipants: Int? = null,
    val actualParticipants: Int? = null
)

@Serializable
data class EventResponse(
    val id: String,
    val tuntasId: String,
    val name: String,
    val type: String,
    val startDate: String,
    val endDate: String,
    val locationId: String? = null,
    val organizationalUnitId: String? = null,
    val createdByUserId: String? = null,
    val status: String,
    val notes: String? = null,
    val createdAt: String,
    val eventRoles: List<EventRoleResponse>,
    val stovyklaDetails: StovyklaDetailsResponse? = null
)

@Serializable
data class EventListResponse(
    val events: List<EventResponse>,
    val total: Int
)