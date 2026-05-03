package lt.skautai.models.responses

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

@Serializable
data class TokenResponse(
    val token: String,
    val refreshToken: String? = null,
    val userId: String,
    val email: String,
    val name: String,
    val type: String = "user",
    val tuntai: List<TuntasInfo> = emptyList()
)

@Serializable
data class MessageResponse(
    val message: String
)

@Serializable(with = ErrorResponseSerializer::class)
data class ErrorResponse(
    val error: String
)

object ErrorResponseSerializer : KSerializer<ErrorResponse> {
    override val descriptor = buildClassSerialDescriptor("ErrorResponse") {
        element<String>("error")
    }

    override fun serialize(encoder: Encoder, value: ErrorResponse) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, sanitizeErrorMessage(value.error))
        }
    }

    override fun deserialize(decoder: Decoder): ErrorResponse {
        var error = "Request failed"
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> error = decodeStringElement(descriptor, 0)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected index: $index")
                }
            }
        }
        return ErrorResponse(error)
    }
}

private val technicalErrorMarkers = listOf(
    "org.jetbrains.exposed",
    "org.postgresql",
    "postgresql",
    "psqlexception",
    "sqlexception",
    "sqlstate",
    "jdbc:",
    "select ",
    "insert ",
    "update ",
    "delete ",
    " from ",
    " where ",
    "constraint",
    "duplicate key",
    "foreign key",
    "stacktrace",
    "exception:",
    "java.net.",
    "failed to connect to",
    "localhost",
    "10.0.2.2"
)

private fun sanitizeErrorMessage(message: String): String {
    val normalized = message.lowercase()
    return if (technicalErrorMarkers.any { it in normalized }) {
        "Request failed"
    } else {
        message
    }
}
