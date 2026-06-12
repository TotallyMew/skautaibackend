package lt.skautai.models.requests

import kotlinx.serialization.Serializable

@Serializable
data class RegisterDeviceRequest(
    val deviceToken: String,
    val deviceName: String? = null
)
