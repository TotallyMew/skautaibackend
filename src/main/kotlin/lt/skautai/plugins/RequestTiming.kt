package lt.skautai.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import lt.skautai.services.OperationalMetrics

fun Application.configureRequestTiming() {
    install(RequestTiming)
}

private val RequestStartNanosKey = AttributeKey<Long>("RequestStartNanos")
private val requestTimingLogger = LoggerFactory.getLogger("RequestTiming")

private val RequestTiming = createApplicationPlugin(name = "RequestTiming") {
    val slowRequestWarnMs = (System.getenv("SLOW_REQUEST_WARN_MS") ?: System.getProperty("SLOW_REQUEST_WARN_MS"))
        ?.toLongOrNull()
        ?: 1_500L

    onCall { call ->
        OperationalMetrics.requestStarted()
        call.attributes.put(RequestStartNanosKey, System.nanoTime())
    }

    onCallRespond { call, _ ->
        val startNanos = call.attributes.getOrNull(RequestStartNanosKey) ?: return@onCallRespond
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000
        val method = call.request.httpMethod.value
        val path = call.request.path()
        val status = call.response.status()?.value ?: 200
        val userId = call.principal<JWTPrincipal>()?.getClaim("userId", String::class) ?: "-"
        val subjectType = call.principal<JWTPrincipal>()?.getClaim("type", String::class) ?: "-"
        val requestId = call.request.header("X-Request-Id") ?: "-"
        val userAgent = call.request.header("User-Agent")?.replace("\"", "'") ?: "-"
        val forwardedFor = call.request.header("X-Forwarded-For")?.substringBefore(",")?.trim()
        val remoteAddress = forwardedFor?.takeIf(String::isNotBlank) ?: call.request.local.remoteHost
        OperationalMetrics.requestCompleted(status, durationMs)
        val message = "access requestId=\"$requestId\" method=$method path=\"$path\" status=$status durationMs=$durationMs remoteAddress=\"$remoteAddress\" userId=\"$userId\" subjectType=\"$subjectType\" userAgent=\"$userAgent\""
        if (durationMs >= slowRequestWarnMs) {
            requestTimingLogger.warn(message)
        } else {
            requestTimingLogger.info(message)
        }
    }
}
