package lt.skautai.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize

fun Application.configureCompression() {
    install(Compression) {
        gzip {
            priority = 1.0
            minimumSize(1_024)
        }
        deflate {
            priority = 0.9
            minimumSize(1_024)
        }
    }
}
