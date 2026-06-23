package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class AssignUnitMemberRequest(
    val userId: String,
    val assignmentType: String = "MEMBER" // MEMBER or VADOVO_PADEJEJAS
)

@Serializable
data class UpdateUnitMemberVisibilityRequest(
    val isPubliclyVisible: Boolean
)
