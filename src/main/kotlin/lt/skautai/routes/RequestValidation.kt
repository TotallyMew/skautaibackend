package lt.skautai.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

private const val shortTextMaxLength = 200
private const val longTextMaxLength = 2_000
private const val urlTextMaxLength = 2_048
private const val tokenTextMaxLength = 4_096

private val longTextFields = setOf(
    "description",
    "itemDescription",
    "notes",
    "reason",
    "rejectionReason",
    "reviewNote",
    "actualLocationNote",
    "returnLocationNote"
)

private val urlTextFields = setOf(
    "photoUrl",
    "invoiceFileUrl",
    "stagedDocumentUrl"
)

private val tokenTextFields = setOf(
    "token",
    "refreshToken",
    "deviceToken"
)

suspend inline fun <reified T : Any> ApplicationCall.receiveValidated(): T {
    val request = receive<T>()
    validateTextLengths(request)?.let { message -> throw BadRequestException(message) }
    return request
}

fun validateTextLengths(value: Any?): String? =
    validateTextLengths(value, value?.javaClass?.simpleName.orEmpty(), IdentityHashMap())

private fun validateTextLengths(value: Any?, path: String, seen: IdentityHashMap<Any, Boolean>): String? {
    if (value == null) return null
    if (value is String) return null
    if (value.javaClass.isPrimitive || value is Number || value is Boolean || value is Enum<*>) return null
    if (seen.put(value, true) != null) return null

    if (value is Iterable<*>) {
        value.forEachIndexed { index, item ->
            validateTextLengths(item, "$path[$index]", seen)?.let { return it }
        }
        return null
    }

    if (value is Map<*, *>) {
        value.values.forEach { item ->
            validateTextLengths(item, path, seen)?.let { return it }
        }
        return null
    }

    value.javaClass.declaredFields
        .filterNot { field -> field.isSynthetic || Modifier.isStatic(field.modifiers) }
        .forEach { field ->
            field.isAccessible = true
            val fieldValue = field.get(value)
            val fieldPath = "$path.${field.name}"
            if (fieldValue is String) {
                val maxLength = maxLengthForField(field.name)
                if (fieldValue.length > maxLength) {
                    return "$fieldPath must be at most $maxLength characters"
                }
            } else {
                validateTextLengths(fieldValue, fieldPath, seen)?.let { return it }
            }
        }

    return null
}

private fun maxLengthForField(fieldName: String): Int = when {
    fieldName in tokenTextFields || fieldName.endsWith("Token") -> tokenTextMaxLength
    fieldName in longTextFields -> longTextMaxLength
    fieldName in urlTextFields || fieldName.endsWith("Url") -> urlTextMaxLength
    else -> shortTextMaxLength
}
