package lt.skautai.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import lt.skautai.database.tables.Tuntai
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.UploadResponse
import lt.skautai.services.PermissionContextService
import lt.skautai.services.UploadService
import lt.skautai.services.UploadRejected
import lt.skautai.util.UploadStorage
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.UUID

private val allowedImageExtensions = setOf("jpg", "jpeg", "png", "webp")
private val allowedDocumentExtensions = setOf("pdf", "jpg", "jpeg", "png")
private val allowedImageContentTypes = setOf("image/jpeg", "image/png", "image/webp")
private val allowedDocumentContentTypes = allowedImageContentTypes + "application/pdf"
private const val maxImageBytes = 5L * 1024 * 1024
private const val maxDocumentBytes = 10L * 1024 * 1024

fun Route.uploadRoutes(apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        if (apiPrefix == "/api") {
            route("/uploads/images") {
            get("{fileName}") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated"))
                val userId = try {
                    UUID.fromString(principal.getClaim("userId", String::class))
                } catch (_: Exception) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                }
                val fileName = call.parameters["fileName"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("File name required"))
                val file = UploadStorage.resolveImage(fileName)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid file name"))

                val tuntasUUID = call.resolveTuntasForUpload(userId)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val url = "${UploadStorage.imageUrlPrefix}/$fileName"
                if (!UploadService.canReadImage(url, tuntasUUID, userId) || !file.exists()) {
                    return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("File not found"))
                }

                call.response.header(HttpHeaders.CacheControl, "private, no-store")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, file.name).toString()
                )
                val contentType = UploadService.contentType(url)?.let { io.ktor.http.ContentType.parse(it) }
                if (contentType == null) call.respondFile(file)
                else call.respond(io.ktor.server.http.content.LocalFileContent(file, contentType))
                }
            }
        }

        post("$apiPrefix/uploads/images") {
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

        post("$apiPrefix/uploads/documents") {
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
    val userId = principal<JWTPrincipal>()?.getClaim("userId", String::class)?.let(UUID::fromString)
        ?: return respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated"))
    val tenant = resolveTuntasForUpload(userId)
        ?: return respond(HttpStatusCode.Forbidden, ErrorResponse("Active tuntas membership required"))
    val ticket = try {
        UploadService.reserve(userId, tenant, if (allowPdf) "DOCUMENT" else "IMAGE", maxBytes)
    } catch (rejected: UploadRejected) {
        return respond(HttpStatusCode.fromValue(rejected.status), ErrorResponse(rejected.message ?: "Upload rejected"))
    }
    var completed = false
    try {
    uploadDir.mkdirs()
    var staged: File? = null
    var uploadedContentType = "application/octet-stream"
    var error: String? = null

    receiveMultipart().forEachPart { part ->
        try {
        if (part is PartData.FileItem && staged == null && error == null) {
            val validation = validateUpload(
                part = part,
                maxBytes = maxBytes,
                allowedExtensions = allowedExtensions,
                allowedContentTypes = allowedContentTypes,
                allowPdf = allowPdf,
                stagedTarget = UploadService.stagingFile(ticket)
            )
            error = validation.error
            if (validation.error == null) {
                val stagedFile = validation.stagedFile
                    ?: throw IllegalStateException("Validated upload did not produce a staged file")
                staged = stagedFile
                uploadedContentType = part.contentType!!.withoutParameters().toString()
            }
        }
        } finally { part.dispose() }
    }

    error?.let {
        respond(HttpStatusCode.BadRequest, ErrorResponse(it))
        return
    }

    val file = staged ?: run {
        respond(HttpStatusCode.BadRequest, ErrorResponse(missingFileMessage))
        return
    }
    UploadService.finish(ticket, file, uploadedContentType)
    completed = true
    respond(HttpStatusCode.Created, UploadResponse(ticket.url, ticket.id.toString()))
    } catch (rejected: UploadRejected) {
        respond(HttpStatusCode.fromValue(rejected.status), ErrorResponse(rejected.message ?: "Upload rejected"))
    } finally {
        if (!completed) UploadService.abandon(ticket)
    }
}

private fun io.ktor.server.application.ApplicationCall.resolveTuntasForUpload(userId: UUID): UUID? {
    request.headers["X-Tuntas-Id"]?.let { headerValue ->
        return try {
            UUID.fromString(headerValue)
        } catch (_: Exception) {
            null
        }
    }

    return transaction {
        UserTuntasMemberships
            .innerJoin(Tuntai, { UserTuntasMemberships.tuntasId }, { Tuntai.id })
            .select(UserTuntasMemberships.tuntasId)
            .where {
                (UserTuntasMemberships.userId eq userId) and
                    UserTuntasMemberships.leftAt.isNull() and
                    (Tuntai.status inList listOf("ACTIVE", "APPROVED"))
            }
            .map { it[UserTuntasMemberships.tuntasId] }
            .distinct()
            .singleOrNull()
    }
}

private fun moveUploadExclusive(uploadDir: File, extension: String, stagedFile: File): String {
    repeat(5) {
        val fileName = "${UUID.randomUUID()}.$extension"
        val target = File(uploadDir, fileName)
        try {
            Files.move(stagedFile.toPath(), target.toPath())
            return fileName
        } catch (_: FileAlreadyExistsException) {
            // Try another UUID below.
        }
    }
    stagedFile.delete()
    throw IllegalStateException("Could not allocate upload file name")
}

private data class UploadValidationResult(
    val stagedFile: File? = null,
    val extension: String = "",
    val error: String? = null
)

private fun validateUpload(
    part: PartData.FileItem,
    maxBytes: Long,
    allowedExtensions: Set<String>,
    allowedContentTypes: Set<String>,
    allowPdf: Boolean,
    stagedTarget: File
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

    val stagedFile = try {
        part.streamProvider().use { input ->
            stageUpload(input, maxBytes, stagedTarget)
        }
    } catch (_: IllegalArgumentException) {
        return UploadValidationResult(error = "File is too large")
    }

    val signature = stagedFile.inputStream().use { input ->
        ByteArray(signatureBytes).also { buffer ->
            val read = input.read(buffer)
            if (read <= 0) return UploadValidationResult(error = "File contents do not match the declared type")
            if (read < buffer.size) return@also
        }.let { buffer ->
            val actualLength = stagedFile.length().coerceAtMost(signatureBytes.toLong()).toInt()
            buffer.copyOf(actualLength)
        }
    }

    val matchesSignature = when {
        isJpeg(signature) -> extension in setOf("jpg", "jpeg") && contentType == "image/jpeg"
        isPng(signature) -> extension == "png" && contentType == "image/png"
        isWebp(signature) -> extension == "webp" && contentType == "image/webp"
        allowPdf && isPdf(signature) -> extension == "pdf" && contentType == "application/pdf"
        else -> false
    }

    if (!matchesSignature) {
        stagedFile.delete()
        return UploadValidationResult(error = "File contents do not match the declared type")
    }

    return UploadValidationResult(stagedFile = stagedFile, extension = extension)
}

private fun stageUpload(input: java.io.InputStream, maxBytes: Long, tempFile: File): File {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L

    try {
        tempFile.outputStream().use { output ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) {
                    break
                }
                total += read
                if (total > maxBytes) {
                    throw IllegalArgumentException("File too large")
                }
                output.write(buffer, 0, read)
            }
        }
        return tempFile
    } catch (e: Exception) {
        tempFile.delete()
        throw e
    }
}

private const val signatureBytes = 16

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
