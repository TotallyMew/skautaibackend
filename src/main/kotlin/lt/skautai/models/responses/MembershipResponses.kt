package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class DraugoveMembershipResponse(
    val id: String,
    val userId: String,
    val userName: String,
    val userSurname: String,
    val organizationalUnitId: String,
    val organizationalUnitName: String,
    val tuntasId: String,
    val isLent: Boolean,
    val assignedByUserId: String?,
    val joinedAt: String,
    val leftAt: String?
)

@Serializable
data class DraugoveMembershipListResponse(
    val members: List<DraugoveMembershipResponse>,
    val total: Int
)