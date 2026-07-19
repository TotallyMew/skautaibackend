package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class RoleResponse(
    val id: String,
    val name: String,
    val roleType: String,
    val isSystemRole: Boolean,
    val canBeInvited: Boolean = true,
    val requiresOrganizationalUnit: Boolean = false,
    val allowedOrganizationalUnitTypes: List<String> = emptyList()
)

@Serializable
data class RoleListResponse(
    val roles: List<RoleResponse>,
    val total: Int
)
