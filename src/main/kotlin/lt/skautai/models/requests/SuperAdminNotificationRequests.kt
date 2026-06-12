package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class SuperAdminNotificationRequest(
    val title: String,
    val body: String,
    val tuntasId: String? = null
)
