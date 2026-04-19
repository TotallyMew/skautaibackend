package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class RoleResponse(
    val id: String,
    val name: String,
    val roleType: String,
    val isSystemRole: Boolean
)

@Serializable
data class RoleListResponse(
    val roles: List<RoleResponse>,
    val total: Int
)