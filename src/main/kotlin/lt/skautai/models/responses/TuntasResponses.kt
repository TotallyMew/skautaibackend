package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class TuntasInfo(
    val id: String,
    val name: String,
    val krastas: String,
    val contactEmail: String,
    val status: String = "ACTIVE"
)
