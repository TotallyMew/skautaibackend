package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class OrganizationalUnitResponse(
    val id: String,
    val tuntasId: String,
    val name: String,
    val type: String,
    val subType: String? = null,
    val acceptedRankId: String? = null,
    val acceptedRankName: String? = null,
    val memberCount: Int = 0,
    val itemCount: Int = 0,
    val createdAt: String
)

@Serializable
data class OrganizationalUnitListResponse(
    val units: List<OrganizationalUnitResponse>,
    val total: Int
)

@Serializable
data class SeniorUnitAccessAuditResponse(
    val id: String,
    val actorUserId: String,
    val actorUserName: String,
    val action: String,
    val accessMode: String,
    val createdAt: String
)

@Serializable
data class SeniorUnitAccessAuditListResponse(
    val entries: List<SeniorUnitAccessAuditResponse>,
    val total: Int
)
