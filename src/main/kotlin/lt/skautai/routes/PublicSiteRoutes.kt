package lt.skautai.routes

import io.ktor.http.ContentType
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.publicSiteRoutes() {
    staticPage("/", "static/index.html", ContentType.Text.Html)
    staticPage("/index.html", "static/index.html", ContentType.Text.Html)
    staticPage("/privacy.html", "static/privacy.html", ContentType.Text.Html)
    staticPage("/delete-account.html", "static/delete-account.html", ContentType.Text.Html)
    staticPage("/styles.css", "static/styles.css", ContentType.Text.CSS)
}

private fun Route.staticPage(path: String, resourcePath: String, contentType: ContentType) {
    get(path) {
        val bytes = object {}.javaClass.classLoader.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: error("Missing bundled resource: $resourcePath")
        call.respondBytes(bytes, contentType)
    }
}
