package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class AssignDraugoveMembershipRequest(
    val userId: String,
    val isLent: Boolean = false
)