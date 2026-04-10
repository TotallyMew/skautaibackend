package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class OrganizationalUnitResponse(
    val id: String,
    val tuntasId: String,
    val parentId: String? = null,
    val name: String,
    val type: String,
    val acceptedRankId: String? = null,
    val acceptedRankName: String? = null,
    val createdAt: String
)

@Serializable
data class OrganizationalUnitListResponse(
    val units: List<OrganizationalUnitResponse>,
    val total: Int
)