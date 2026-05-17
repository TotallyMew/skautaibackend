package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class MyTaskResponse(
    val id: String,
    val type: String,
    val title: String,
    val subtitle: String,
    val count: Int? = null,
    val priority: Int,
    val urgency: String,
    val bucket: String,
    val routeTarget: String,
    val createdAt: String,
    val dueAt: String? = null,
    val entityId: String? = null
)

@Serializable
data class MyTaskListResponse(
    val tasks: List<MyTaskResponse>,
    val total: Int
)
