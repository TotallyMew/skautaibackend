package lt.skautai.services

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object OperationalMetrics {
    private val startedAtMs = System.currentTimeMillis()
    private val requests = AtomicLong()
    private val activeRequests = AtomicLong()
    private val failedRequests = AtomicLong()
    private val unhandledErrors = AtomicLong()
    private val rateLimitedRequests = AtomicLong()
    private val oversizedRequests = AtomicLong()
    private val totalDurationMs = AtomicLong()
    private val maxDurationMs = AtomicLong()
    private val statusClasses = ConcurrentHashMap<Int, AtomicLong>()

    fun requestStarted() {
        requests.incrementAndGet()
        activeRequests.incrementAndGet()
    }

    fun requestCompleted(status: Int, durationMs: Long) {
        activeRequests.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        if (status >= 500) failedRequests.incrementAndGet()
        statusClasses.computeIfAbsent(status / 100) { AtomicLong() }.incrementAndGet()
        totalDurationMs.addAndGet(durationMs)
        maxDurationMs.updateAndGet { current -> maxOf(current, durationMs) }
    }

    fun unhandledError() {
        unhandledErrors.incrementAndGet()
    }

    fun rateLimitedRequest() {
        rateLimitedRequests.incrementAndGet()
    }

    fun oversizedRequestRejected() {
        oversizedRequests.incrementAndGet()
    }

    fun uptimeSeconds(): Long = (System.currentTimeMillis() - startedAtMs) / 1_000

    fun prometheus(): String {
        val requestCount = requests.get()
        val averageDuration = if (requestCount == 0L) 0.0 else totalDurationMs.get().toDouble() / requestCount
        return buildString {
            appendLine("# TYPE skautai_uptime_seconds gauge")
            appendLine("skautai_uptime_seconds ${uptimeSeconds()}")
            appendLine("# TYPE skautai_http_requests_total counter")
            appendLine("skautai_http_requests_total $requestCount")
            appendLine("# TYPE skautai_http_active_requests gauge")
            appendLine("skautai_http_active_requests ${activeRequests.get()}")
            appendLine("# TYPE skautai_http_failed_requests_total counter")
            appendLine("skautai_http_failed_requests_total ${failedRequests.get()}")
            appendLine("# TYPE skautai_unhandled_errors_total counter")
            appendLine("skautai_unhandled_errors_total ${unhandledErrors.get()}")
            appendLine("# TYPE skautai_rate_limited_requests_total counter")
            appendLine("skautai_rate_limited_requests_total ${rateLimitedRequests.get()}")
            appendLine("# TYPE skautai_oversized_requests_total counter")
            appendLine("skautai_oversized_requests_total ${oversizedRequests.get()}")
            appendLine("# TYPE skautai_http_request_duration_ms_average gauge")
            appendLine("skautai_http_request_duration_ms_average $averageDuration")
            appendLine("# TYPE skautai_http_request_duration_ms_max gauge")
            appendLine("skautai_http_request_duration_ms_max ${maxDurationMs.get()}")
            statusClasses.toSortedMap().forEach { (statusClass, count) ->
                appendLine("skautai_http_responses_total{status_class=\"${statusClass}xx\"} ${count.get()}")
            }
        }
    }
}
