package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class UnitMembershipResponse(
    val id: String,
    val userId: String,
    val userName: String,
    val userSurname: String,
    val organizationalUnitId: String,
    val organizationalUnitName: String,
    val tuntasId: String,
    val assignmentType: String,
    val assignedByUserId: String?,
    val joinedAt: String,
    val leftAt: String?
)

@Serializable
data class UnitMembershipListResponse(
    val members: List<UnitMembershipResponse>,
    val total: Int
)