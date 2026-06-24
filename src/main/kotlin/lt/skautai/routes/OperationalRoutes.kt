package lt.skautai.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import lt.skautai.services.OperationalMetrics
import lt.skautai.util.UploadStorage
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files

fun Route.operationalRoutes() {
    get("/health/live") {
        call.respond(
            mapOf(
                "status" to "UP",
                "uptimeSeconds" to OperationalMetrics.uptimeSeconds().toString()
            )
        )
    }

    get("/health/ready") {
        val databaseReady = runCatching {
            transaction { exec("SELECT 1") }
        }.isSuccess
        val uploadsReady = runCatching {
            val directory = UploadStorage.rootDir().toPath()
            Files.createDirectories(directory)
            Files.isWritable(directory)
        }.getOrDefault(false)
        val ready = databaseReady && uploadsReady
        call.respond(
            if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            mapOf(
                "status" to if (ready) "UP" else "DOWN",
                "database" to if (databaseReady) "UP" else "DOWN",
                "uploads" to if (uploadsReady) "UP" else "DOWN"
            )
        )
    }

    get("/metrics") {
        val configuredToken = System.getenv("METRICS_TOKEN")
            ?: System.getProperty("METRICS_TOKEN")
        if (configuredToken.isNullOrBlank()) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        if (call.request.headers["X-Metrics-Token"] != configuredToken) {
            call.respond(HttpStatusCode.Unauthorized)
            return@get
        }
        call.respondText(OperationalMetrics.prometheus(), ContentType.Text.Plain)
    }
}
