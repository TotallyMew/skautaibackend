package lt.skautai.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import com.auth0.jwt.JWT
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

val PublicAuthRateLimit = RateLimitName("public-auth")
val RegistrationRateLimit = RateLimitName("registration")
val EmailRequestRateLimit = RateLimitName("email-request")
val AuthenticatedApiRateLimit = RateLimitName("authenticated-api")
val MutationRateLimit = RateLimitName("mutation")
val UploadRateLimit = RateLimitName("upload")
val SearchRateLimit = RateLimitName("search")
val ExpensiveApiRateLimit = RateLimitName("expensive-api")
val MessagingRateLimit = RateLimitName("messaging")

fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(PublicAuthRateLimit) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey(::clientIpKey)
        }
        register(RegistrationRateLimit) {
            rateLimiter(limit = settingInt("REGISTRATION_RATE_LIMIT", 3), refillPeriod = 1.hours)
            requestKey(::clientIpKey)
        }
        register(EmailRequestRateLimit) {
            rateLimiter(limit = settingInt("EMAIL_REQUEST_RATE_LIMIT", 5), refillPeriod = 1.hours)
            requestKey(::clientIpKey)
        }
        register(AuthenticatedApiRateLimit) {
            rateLimiter(limit = 300, refillPeriod = 1.minutes)
            requestKey(::authenticatedClientKey)
        }
        register(MutationRateLimit) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey(::authenticatedClientKey)
            requestWeight { call, _ ->
                if (call.request.httpMethod in mutatingMethods) 1 else 0
            }
        }
        register(UploadRateLimit) {
            rateLimiter(limit = 120, refillPeriod = 10.minutes)
            requestKey(::authenticatedClientKey)
        }
        register(SearchRateLimit) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey(::authenticatedEndpointKey)
        }
        register(ExpensiveApiRateLimit) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey(::authenticatedEndpointKey)
        }
        register(MessagingRateLimit) {
            rateLimiter(limit = 30, refillPeriod = 1.hours)
            requestKey(::authenticatedEndpointKey)
        }
    }
}

private val mutatingMethods = setOf(
    HttpMethod.Post,
    HttpMethod.Put,
    HttpMethod.Patch,
    HttpMethod.Delete
)

private fun clientIpKey(call: ApplicationCall): String {
    val remoteHost = call.request.origin.remoteHost
    if (!trustsForwardedHeadersFrom(remoteHost)) return "ip:$remoteHost"

    val forwarded = call.request.header("X-Forwarded-For")
        ?.substringBefore(',')
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val realIp = call.request.header("X-Real-IP")?.trim()?.takeIf(String::isNotBlank)
    return "ip:${forwarded ?: realIp ?: remoteHost}"
}

private fun trustsForwardedHeadersFrom(remoteHost: String): Boolean {
    val trustedProxies = setting("TRUSTED_PROXY_IPS")
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    if (trustedProxies.isEmpty()) return false
    return remoteHost in trustedProxies
}

private fun authenticatedClientKey(call: ApplicationCall): String {
    val authorization = call.request.header(HttpHeaders.Authorization)
        ?.removePrefix("Bearer ")
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val subject = authorization?.let(::jwtSubjectKey)
    return subject ?: authorization?.let { "auth:${shortHash(it)}" } ?: clientIpKey(call)
}

private fun authenticatedEndpointKey(call: ApplicationCall): String =
    "${authenticatedClientKey(call)}:${call.request.httpMethod.value}:${call.request.path()}"

private fun jwtSubjectKey(token: String): String? = runCatching {
    val decoded = JWT.decode(token)
    val type = decoded.getClaim("type").asString()?.takeIf(String::isNotBlank) ?: "unknown"
    val userId = decoded.getClaim("userId").asString()?.takeIf(String::isNotBlank) ?: return@runCatching null
    "acct:$type:$userId"
}.getOrNull()

private fun shortHash(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it) }

private fun setting(name: String): String? =
    (System.getenv(name) ?: System.getProperty(name))?.trim()?.takeIf(String::isNotBlank)

private fun settingInt(name: String, default: Int): Int =
    setting(name)?.toIntOrNull()?.takeIf { it > 0 } ?: default
