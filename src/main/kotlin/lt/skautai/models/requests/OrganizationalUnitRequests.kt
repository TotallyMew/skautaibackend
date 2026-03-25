package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreateOrganizationalUnitRequest(
    val name: String,
    val type: String,
    val parentId: String? = null
)

@Serializable
data class UpdateOrganizationalUnitRequest(
    val name: String? = null,
    val parentId: String? = null
)