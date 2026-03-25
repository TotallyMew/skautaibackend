package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class MemberLeadershipRoleResponse(
    val id: String,
    val roleId: String,
    val roleName: String,
    val organizationalUnitId: String? = null,
    val organizationalUnitName: String? = null,
    val assignedByUserId: String? = null,
    val assignedAt: String,
    val startsAt: String? = null,
    val expiresAt: String? = null,
    val leftAt: String? = null,
    val termNumber: Int,
    val termStatus: String
)

@Serializable
data class MemberRankResponse(
    val id: String,
    val roleId: String,
    val roleName: String,
    val assignedByUserId: String? = null,
    val assignedAt: String
)

@Serializable
data class MemberResponse(
    val userId: String,
    val name: String,
    val surname: String,
    val email: String,
    val phone: String? = null,
    val joinedAt: String,
    val leadershipRoles: List<MemberLeadershipRoleResponse>,
    val ranks: List<MemberRankResponse>
)

@Serializable
data class MemberListResponse(
    val members: List<MemberResponse>,
    val total: Int
)