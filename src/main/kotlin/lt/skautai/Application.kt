package lt.skautai

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import lt.skautai.database.tables.Tuntai
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.plugins.configureRouting
import lt.skautai.plugins.configureLiveEventPublisher
import lt.skautai.plugins.configureSecurity
import lt.skautai.plugins.configureSerialization
import lt.skautai.services.PermissionSeeder
import lt.skautai.services.VadovasRankSupport
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

fun main(args: Array<String>) {
    loadDotEnvIntoSystemProperties()
    EngineMain.main(args)
}

fun Application.module() {
    loadDotEnvIntoSystemProperties()
    configureDatabases()
    configureSerialization()
    configureSecurity()
    configureLiveEventPublisher()
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
    }
    install(StatusPages) {
        exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }
    configureRouting()
    PermissionSeeder.seedPermissions()
    transaction {
        Tuntai.selectAll().map { it[Tuntai.id] }.forEach { tuntasId ->
            PermissionSeeder.seedRolePermissions(tuntasId)
        }
    }
    VadovasRankSupport.backfillExistingLeadershipUsers()
}

fun Application.configureDatabases() {
    val config = environment.config
    val url = config.property("database.url").getString()
    val driver = config.property("database.driver").getString()
    val user = config.property("database.user").getString()
    val password = config.property("database.password").getString()

    Database.connect(
        url = url,
        driver = driver,
        user = user,
        password = password
    )

    val logger = log
    Flyway.configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("1")
        .baselineDescription("Existing schema baseline")
        .load()
        .migrate()

    transaction {
        exec("SELECT 1")
        logger.info("Database connected successfully")
    }
}

private val supportedDotEnvKeys = setOf(
    "DB_URL",
    "DB_USER",
    "DB_PASSWORD",
    "JWT_SECRET",
    "SETUP_BOOTSTRAP_TOKEN",
    "FIREBASE_SERVICE_ACCOUNT_PATH",
    "NOTIFICATIONS_TEST_ENABLED",
    "PORT"
)

private fun loadDotEnvIntoSystemProperties(dotEnvPath: Path = Path(".env")) {
    val resolvedDotEnvPath = listOf(
        dotEnvPath,
        Path("skautu-inventoriaus-valdymas-backend/.env")
    ).firstOrNull { Files.exists(it) } ?: return

    Files.readAllLines(resolvedDotEnvPath).forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#")) return@forEach

        val normalizedLine = if (line.startsWith("export ")) {
            line.removePrefix("export ").trim()
        } else {
            line
        }

        val separatorIndex = normalizedLine.indexOf('=')
        if (separatorIndex <= 0) return@forEach

        val key = normalizedLine.substring(0, separatorIndex).trim()
        if (key !in supportedDotEnvKeys) return@forEach

        val value = normalizedLine.substring(separatorIndex + 1).trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")

        if (System.getenv(key) == null && System.getProperty(key) == null) {
            System.setProperty(key, value)
        }
    }
}
