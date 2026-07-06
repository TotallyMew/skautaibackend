plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
}

group = "lt.skautai"
version = "1.0.0"

val ktor_version = "3.4.1"
val exposed_version = "0.54.0"
val logback_version = "1.4.14"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("io.ktor:ktor-server-auth:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jwt:$ktor_version")
    implementation("io.ktor:ktor-server-status-pages:$ktor_version")
    implementation("io.ktor:ktor-server-default-headers:$ktor_version")
    implementation("io.ktor:ktor-server-compression:$ktor_version")
    implementation("io.ktor:ktor-server-cors:$ktor_version")
    implementation("io.ktor:ktor-server-rate-limit:$ktor_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-json:$exposed_version")

    // Database
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logback_version")

    // Authentication
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("com.auth0:java-jwt:4.4.0")

    // Push notifications
    implementation("com.google.firebase:firebase-admin:9.6.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor_version")
    testImplementation("io.mockk:mockk:1.13.10")
}

application {
    mainClass.set("lt.skautai.ApplicationKt")
}

val coverageExclusions = listOf(
    "lt.skautai.ApplicationKt*",
    "lt.skautai.plugins.RoutingKt*",
    "lt.skautai.plugins.SerializationKt*",
    "*\$*"
)

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    environment("TEST_DB_PASSWORD", System.getenv("TEST_DB_PASSWORD") ?: "")
    environment("TEST_DB_URL", System.getenv("TEST_DB_URL") ?: "jdbc:postgresql://localhost:5432/skautu_inventorius_test")
    environment("TEST_DB_USER", System.getenv("TEST_DB_USER") ?: "postgres")
    environment("TEST_JWT_SECRET", System.getenv("TEST_JWT_SECRET") ?: "")
}

kover {
    reports {
        filters {
            excludes {
                classes(*coverageExclusions.toTypedArray())
            }
        }
    }
}

tasks.register("coverageReport") {
    group = "verification"
    description = "Runs backend tests and generates a single overall Kover HTML coverage report."
    dependsOn("koverHtmlReport")
}

tasks.register("coverageSummary") {
    group = "verification"
    description = "Runs backend tests and prints a single overall Kover coverage summary to the console."
    dependsOn("koverLog")
}

kotlin {
    jvmToolchain(21)
}

