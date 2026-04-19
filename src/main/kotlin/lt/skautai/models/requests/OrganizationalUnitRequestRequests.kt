package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class CreateOrganizationalUnitRequest(
    val name: String,
    val type: String,
    val subType: String? = null,
    val acceptedRankId: String? = null
)

@Serializable
data class UpdateOrganizationalUnitRequest(
    val name: String? = null,
    val acceptedRankId: String? = null
)