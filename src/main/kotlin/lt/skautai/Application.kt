package lt.skautai

import io.ktor.http.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import lt.skautai.database.tables.Tuntai
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.plugins.configureCompression
import lt.skautai.plugins.configureRouting
import lt.skautai.plugins.configureLiveEventPublisher
import lt.skautai.plugins.configureRequestTiming
import lt.skautai.plugins.configureSecurity
import lt.skautai.plugins.configureSerialization
import lt.skautai.services.PermissionSeeder
import lt.skautai.services.VadovasRankSupport
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
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
    configureCompression()
    configureRequestTiming()
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
    val databaseSettings = resolveDatabaseSettings(config)
    val driver = config.property("database.driver").getString()
    val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = databaseSettings.url
            driverClassName = driver
            username = databaseSettings.user
            password = databaseSettings.password
            maximumPoolSize = systemInt("DB_POOL_MAX_SIZE", 5)
            minimumIdle = systemInt("DB_POOL_MIN_IDLE", 1)
            connectionTimeout = systemLong("DB_POOL_CONNECTION_TIMEOUT_MS", 10_000L)
            validationTimeout = systemLong("DB_POOL_VALIDATION_TIMEOUT_MS", 3_000L)
            idleTimeout = systemLong("DB_POOL_IDLE_TIMEOUT_MS", 600_000L)
            maxLifetime = systemLong("DB_POOL_MAX_LIFETIME_MS", 1_500_000L)
            poolName = "skautai-db"
        }
    )

    Database.connect(dataSource)

    val logger = log
    Flyway.configure()
        .dataSource(dataSource)
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

private data class DatabaseSettings(
    val url: String,
    val user: String,
    val password: String
)

private fun resolveDatabaseSettings(config: ApplicationConfig): DatabaseSettings {
    val railwayUrl = System.getenv("DATABASE_URL")
        ?: System.getProperty("DATABASE_URL")
    val railwayPrivateUrl = System.getenv("DATABASE_PRIVATE_URL")
        ?: System.getProperty("DATABASE_PRIVATE_URL")
    val railwayPublicUrl = System.getenv("DATABASE_PUBLIC_URL")
        ?: System.getProperty("DATABASE_PUBLIC_URL")

    val parsedRailway = railwayUrl?.let(::parseDatabaseUrl)
    val parsedPrivateRailway = railwayPrivateUrl?.let(::parseDatabaseUrl)
    val parsedPublicRailway = railwayPublicUrl?.let(::parseDatabaseUrl)
    val configuredUrl = System.getenv("DB_URL")
        ?: System.getProperty("DB_URL")
        ?: config.property("database.url").getString()
    val configuredUser = System.getenv("DB_USER")
        ?: System.getProperty("DB_USER")
        ?: config.property("database.user").getString()
    val configuredPassword = System.getenv("DB_PASSWORD")
        ?: System.getProperty("DB_PASSWORD")
        ?: config.propertyOrNull("database.password")?.getString()
        ?: ""

    return DatabaseSettings(
        url = parsedPrivateRailway?.url ?: parsedRailway?.url ?: configuredUrl.takeUnless { railwayPublicUrl != null } ?: parsedPublicRailway?.url ?: configuredUrl,
        user = parsedPrivateRailway?.user?.takeIf { it.isNotBlank() }
            ?: parsedRailway?.user?.takeIf { it.isNotBlank() }
            ?: configuredUser.takeUnless { railwayPublicUrl != null }
            ?: parsedPublicRailway?.user
            ?: configuredUser,
        password = parsedPrivateRailway?.password
            ?: parsedRailway?.password
            ?: configuredPassword.takeUnless { railwayPublicUrl != null }
            ?: parsedPublicRailway?.password
            ?: configuredPassword
    )
}

private fun systemInt(key: String, default: Int): Int =
    (System.getenv(key) ?: System.getProperty(key))?.toIntOrNull() ?: default

private fun systemLong(key: String, default: Long): Long =
    (System.getenv(key) ?: System.getProperty(key))?.toLongOrNull() ?: default

private fun parseDatabaseUrl(rawUrl: String): DatabaseSettings? {
    if (rawUrl.startsWith("jdbc:postgresql://")) {
        return DatabaseSettings(rawUrl, "", "")
    }
    if (!rawUrl.startsWith("postgres://") && !rawUrl.startsWith("postgresql://")) return null

    val uri = URI(rawUrl)
    val userInfo = uri.rawUserInfo.orEmpty().split(":", limit = 2)
    val user = userInfo.getOrNull(0).orEmpty().urlDecode()
    val password = userInfo.getOrNull(1).orEmpty().urlDecode()
    val port = if (uri.port > 0) ":${uri.port}" else ""
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    val jdbcUrl = "jdbc:postgresql://${uri.host}$port${uri.rawPath}$query"
    return DatabaseSettings(jdbcUrl, user, password)
}

private fun String.urlDecode(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8)

private val supportedDotEnvKeys = setOf(
    "DB_URL",
    "DB_USER",
    "DB_PASSWORD",
    "DATABASE_URL",
    "DATABASE_PRIVATE_URL",
    "DATABASE_PUBLIC_URL",
    "JWT_SECRET",
    "SETUP_BOOTSTRAP_TOKEN",
    "FIREBASE_SERVICE_ACCOUNT_PATH",
    "NOTIFICATIONS_TEST_ENABLED",
    "UPLOADS_DIR",
    "PORT",
    "DB_POOL_MAX_SIZE",
    "DB_POOL_MIN_IDLE",
    "DB_POOL_CONNECTION_TIMEOUT_MS",
    "DB_POOL_VALIDATION_TIMEOUT_MS",
    "DB_POOL_IDLE_TIMEOUT_MS",
    "DB_POOL_MAX_LIFETIME_MS"
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
