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
import java.io.File
import java.util.UUID

private val allowedImageExtensions = setOf("jpg", "jpeg", "png", "webp")

fun Route.uploadRoutes() {
    route("/uploads/images") {
        get("{fileName}") {
            val fileName = call.parameters["fileName"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("File name required"))
            if (fileName.contains("/") || fileName.contains("\\")) {
                return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid file name"))
            }

            val file = File("uploads/images", fileName)
            if (!file.exists()) {
                return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("File not found"))
            }

            call.respondFile(file)
        }
    }

    authenticate("auth-jwt") {
        post("/api/uploads/images") {
            val uploadDir = File("uploads/images").apply { mkdirs() }
            var uploadedUrl: String? = null
            var error: String? = null

            call.receiveMultipart().forEachPart { part ->
                if (part is PartData.FileItem && uploadedUrl == null && error == null) {
                    val originalName = part.originalFileName.orEmpty()
                    val extension = originalName.substringAfterLast('.', "jpg").lowercase()
                    if (extension !in allowedImageExtensions) {
                        error = "Unsupported image type"
                    } else {
                        val fileName = "${UUID.randomUUID()}.$extension"
                        val target = File(uploadDir, fileName)
                        part.streamProvider().use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        uploadedUrl = "/uploads/images/$fileName"
                    }
                }
                part.dispose()
            }

            error?.let {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
            }
            val url = uploadedUrl
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Image file required"))
            call.respond(HttpStatusCode.Created, UploadResponse(url))
        }
    }
}
