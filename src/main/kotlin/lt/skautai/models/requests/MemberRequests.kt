package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class AssignLeadershipRoleRequest(
    val roleId: String,
    val organizationalUnitId: String? = null,
    val startsAt: String? = null,
    val expiresAt: String? = null,
    val termNumber: Int = 1
)

@Serializable
data class UpdateLeadershipRoleRequest(
    val startsAt: String? = null,
    val expiresAt: String? = null,
    val termStatus: String? = null,
    val organizationalUnitId: String? = null
)

@Serializable
data class TransferTuntininkasRequest(
    val successorUserId: String
)

@Serializable
data class CreateLeadershipChangeRequest(
    val reason: String? = null
)

@Serializable
data class ReviewLeadershipChangeRequest(
    val action: String,
    val successorUserId: String? = null,
    val reviewNote: String? = null
)

@Serializable
data class AssignRankRequest(
    val roleId: String
)
