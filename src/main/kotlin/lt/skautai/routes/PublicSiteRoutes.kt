package lt.skautai.routes

import io.ktor.http.ContentType
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.publicSiteRoutes() {
    staticPage("/", "static/index.html", ContentType.Text.Html)
    staticPage("/index.html", "static/index.html", ContentType.Text.Html)
    staticPage("/privacy.html", "static/privacy.html", ContentType.Text.Html)
    staticPage("/delete-account.html", "static/delete-account.html", ContentType.Text.Html)
    staticPage("/styles.css", "static/styles.css", ContentType.Text.CSS)
    get("/.well-known/assetlinks.json") {
        call.respondText(androidAssetLinksJson(), ContentType.Application.Json)
    }
}

private fun Route.staticPage(path: String, resourcePath: String, contentType: ContentType) {
    get(path) {
        val bytes = object {}.javaClass.classLoader.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: error("Missing bundled resource: $resourcePath")
        call.respondBytes(bytes, contentType)
    }
}

private fun androidAssetLinksJson(): String {
    val fingerprints = (System.getenv("ANDROID_APP_CERT_SHA256") ?: System.getProperty("ANDROID_APP_CERT_SHA256"))
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.matches(Regex("(?i)^([0-9a-f]{2}:){31}[0-9a-f]{2}$")) }
        .orEmpty()
    if (fingerprints.isEmpty()) return "[]"
    val fingerprintJson = fingerprints.joinToString(",") { "\"${it.uppercase()}\"" }
    return """
        [{
          "relation": ["delegate_permission/common.handle_all_urls"],
          "target": {
            "namespace": "android_app",
            "package_name": "lt.skautai.android",
            "sha256_cert_fingerprints": [$fingerprintJson]
          }
        }]
    """.trimIndent()
}
