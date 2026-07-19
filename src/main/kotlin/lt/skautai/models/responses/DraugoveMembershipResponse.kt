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
    val isPubliclyVisible: Boolean = false,
    val canManageVisibility: Boolean = false,
    val assignedByUserId: String?,
    val joinedAt: String,
    val leftAt: String?,
    val isIdentityHidden: Boolean = false
)

@Serializable
data class UnitMembershipListResponse(
    val members: List<UnitMembershipResponse>,
    val total: Int
)
