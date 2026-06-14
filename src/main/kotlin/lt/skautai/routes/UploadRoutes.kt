package lt.skautai.routes

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.UploadResponse
import lt.skautai.util.UploadStorage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

private val allowedImageExtensions = setOf("jpg", "jpeg", "png", "webp")
private val allowedDocumentExtensions = setOf("pdf", "jpg", "jpeg", "png")
private val allowedImageContentTypes = setOf("image/jpeg", "image/png", "image/webp")
private val allowedDocumentContentTypes = allowedImageContentTypes + "application/pdf"
private const val maxImageBytes = 5L * 1024 * 1024
private const val maxDocumentBytes = 10L * 1024 * 1024

fun Route.uploadRoutes() {
    authenticate("auth-jwt") {
        route("/uploads/images") {
            get("{fileName}") {
                val fileName = call.parameters["fileName"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("File name required"))
                val file = UploadStorage.resolveImage(fileName)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid file name"))

                if (!file.exists()) {
                    return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("File not found"))
                }

                call.respondFile(file)
            }
        }

        post("/api/uploads/images") {
            call.handleUpload(
                uploadDir = UploadStorage.imagesDir(),
                maxBytes = maxImageBytes,
                allowedExtensions = allowedImageExtensions,
                allowedContentTypes = allowedImageContentTypes,
                allowPdf = false,
                urlPrefix = UploadStorage.imageUrlPrefix,
                missingFileMessage = "Image file required"
            )
        }

        post("/api/uploads/documents") {
            call.handleUpload(
                uploadDir = UploadStorage.documentsDir(),
                maxBytes = maxDocumentBytes,
                allowedExtensions = allowedDocumentExtensions,
                allowedContentTypes = allowedDocumentContentTypes,
                allowPdf = true,
                urlPrefix = UploadStorage.documentUrlPrefix,
                missingFileMessage = "Document file required"
            )
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.handleUpload(
    uploadDir: File,
    maxBytes: Long,
    allowedExtensions: Set<String>,
    allowedContentTypes: Set<String>,
    allowPdf: Boolean,
    urlPrefix: String,
    missingFileMessage: String
) {
    uploadDir.mkdirs()
    var uploadedUrl: String? = null
    var error: String? = null

    receiveMultipart().forEachPart { part ->
        if (part is PartData.FileItem && uploadedUrl == null && error == null) {
            val validation = validateUpload(
                part = part,
                maxBytes = maxBytes,
                allowedExtensions = allowedExtensions,
                allowedContentTypes = allowedContentTypes,
                allowPdf = allowPdf
            )
            error = validation.error
            if (validation.error == null) {
                val fileName = "${UUID.randomUUID()}.${validation.extension}"
                val target = File(uploadDir, fileName)
                target.writeBytes(validation.bytes)
                uploadedUrl = "$urlPrefix/$fileName"
            }
        }
        part.dispose()
    }

    error?.let {
        respond(HttpStatusCode.BadRequest, ErrorResponse(it))
        return
    }

    val url = uploadedUrl ?: run {
        respond(HttpStatusCode.BadRequest, ErrorResponse(missingFileMessage))
        return
    }
    respond(HttpStatusCode.Created, UploadResponse(url))
}

private data class UploadValidationResult(
    val bytes: ByteArray = ByteArray(0),
    val extension: String = "",
    val error: String? = null
)

private fun validateUpload(
    part: PartData.FileItem,
    maxBytes: Long,
    allowedExtensions: Set<String>,
    allowedContentTypes: Set<String>,
    allowPdf: Boolean
): UploadValidationResult {
    val originalName = part.originalFileName?.trim().orEmpty()
    if (originalName.isBlank() || !originalName.contains('.')) {
        return UploadValidationResult(error = "Original file name is required")
    }

    val extension = originalName.substringAfterLast('.').lowercase()
    if (extension !in allowedExtensions) {
        return UploadValidationResult(error = "Unsupported file type")
    }

    val contentType = part.contentType?.withoutParameters()?.toString()?.lowercase()
        ?: return UploadValidationResult(error = "Content-Type is required")
    if (contentType !in allowedContentTypes) {
        return UploadValidationResult(error = "Unsupported Content-Type")
    }

    val bytes = try {
        part.streamProvider().use { it.readUpTo(maxBytes) }
    } catch (_: IllegalArgumentException) {
        return UploadValidationResult(error = "File is too large")
    }

    val matchesSignature = when {
        isJpeg(bytes) -> extension in setOf("jpg", "jpeg") && contentType == "image/jpeg"
        isPng(bytes) -> extension == "png" && contentType == "image/png"
        isWebp(bytes) -> extension == "webp" && contentType == "image/webp"
        allowPdf && isPdf(bytes) -> extension == "pdf" && contentType == "application/pdf"
        else -> false
    }

    if (!matchesSignature) {
        return UploadValidationResult(error = "File contents do not match the declared type")
    }

    return UploadValidationResult(bytes = bytes, extension = extension)
}

private fun InputStream.readUpTo(maxBytes: Long): ByteArray {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream()
    var total = 0L

    while (true) {
        val read = read(buffer)
        if (read == -1) {
            break
        }
        total += read
        if (total > maxBytes) {
            throw IllegalArgumentException("File too large")
        }
        output.write(buffer, 0, read)
    }

    return output.toByteArray()
}

private fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()

private fun isPng(bytes: ByteArray): Boolean =
    bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte() &&
        bytes[4] == 0x0D.toByte() &&
        bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() &&
        bytes[7] == 0x0A.toByte()

private fun isWebp(bytes: ByteArray): Boolean =
    bytes.size >= 12 &&
        String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) == "RIFF" &&
        String(bytes.copyOfRange(8, 12), Charsets.US_ASCII) == "WEBP"

private fun isPdf(bytes: ByteArray): Boolean =
    bytes.size >= 5 && String(bytes.copyOfRange(0, 5), Charsets.US_ASCII) == "%PDF-"
