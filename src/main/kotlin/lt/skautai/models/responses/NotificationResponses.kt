package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class NotificationResponse(
    val id: String,
    val tuntasId: String? = null,
    val title: String,
    val body: String,
    val resource: String? = null,
    val entityId: String? = null,
    val data: Map<String, String> = emptyMap(),
    val readAt: String? = null,
    val createdAt: String
)

@Serializable
data class NotificationListResponse(
    val notifications: List<NotificationResponse>,
    val total: Int,
    val unreadCount: Int
)
