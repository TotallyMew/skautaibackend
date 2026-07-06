package lt.skautai.plugins

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import lt.skautai.services.OperationalMetrics
import org.slf4j.LoggerFactory

class RequestBodyTooLargeException(val limitBytes: Long) : RuntimeException()
class RequestLengthRequiredException : RuntimeException()

fun Application.configureRequestBodyLimits() {
    install(RequestBodyLimits)
}

private val bodyLimitLogger = LoggerFactory.getLogger("RequestBodyLimits")

private val RequestBodyLimits = createApplicationPlugin(name = "RequestBodyLimits") {
    val defaultLimitBytes = settingLong("MAX_API_BODY_BYTES", 1L * 1024 * 1024)
    val uploadLimitBytes = settingLong("MAX_UPLOAD_BODY_BYTES", 11L * 1024 * 1024)

    onCall { call ->
        val method = call.request.httpMethod
        if (method !in setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)) return@onCall

        val path = call.request.path()
        if (!path.startsWith("/api/")) return@onCall

        val limit = if (path.isUploadPath()) uploadLimitBytes else defaultLimitBytes
        val contentLength = call.request.contentLength()
        if (contentLength == null) {
            if (method == HttpMethod.Delete) return@onCall
            OperationalMetrics.oversizedRequestRejected()
            bodyLimitLogger.warn(
                "rejecting request without content length method={} path={} remoteHost={}",
                method.value,
                path,
                call.request.origin.remoteHost
            )
            throw RequestLengthRequiredException()
        }
        if (contentLength <= limit) return@onCall

        OperationalMetrics.oversizedRequestRejected()
        bodyLimitLogger.warn(
            "rejecting oversized request method={} path={} remoteHost={} contentLength={} limit={}",
            method.value,
            path,
            call.request.origin.remoteHost,
            contentLength,
            limit
        )
        throw RequestBodyTooLargeException(limit)
    }
}

private fun settingLong(name: String, default: Long): Long =
    (System.getenv(name) ?: System.getProperty(name))?.toLongOrNull() ?: default

private fun String.isUploadPath(): Boolean =
    startsWith("/api/uploads/") || startsWith("/api/v1/uploads/")
