package lt.skautai

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import lt.skautai.plugins.configureRouting
import lt.skautai.plugins.configureSecurity
import lt.skautai.plugins.configureSerialization
import lt.skautai.database.tables.Tuntai
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.services.PermissionSeeder
import lt.skautai.services.VadovasRankSupport
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureDatabases()
    configureSerialization()
    configureSecurity()
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
    transaction {
        exec("SELECT 1")
        logger.info("Database connected successfully")
    }
}
