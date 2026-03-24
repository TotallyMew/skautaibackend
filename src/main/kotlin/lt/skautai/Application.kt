package lt.skautai

import io.ktor.server.application.*
import io.ktor.server.netty.*
import lt.skautai.plugins.configureRouting
import lt.skautai.plugins.configureSecurity
import lt.skautai.plugins.configureSerialization
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureDatabases()
    configureSerialization()
    configureSecurity()
    configureRouting()
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
        logger.info("Database connection successful")
    }
}