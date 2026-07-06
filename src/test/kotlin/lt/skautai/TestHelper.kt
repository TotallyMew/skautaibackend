package lt.skautai

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.*
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.plugins.configureSecurity
import lt.skautai.plugins.configureRateLimiting
import lt.skautai.routes.authRoutes
import lt.skautai.routes.invitationRoutes
import lt.skautai.routes.inventoryKitRoutes
import lt.skautai.routes.inventoryTemplateRoutes
import lt.skautai.routes.itemRoutes
import lt.skautai.routes.superAdminRoutes
import lt.skautai.services.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import lt.skautai.plugins.configureSerialization
import lt.skautai.routes.bendrasInventoryRequestRoutes
import lt.skautai.routes.locationRoutes
import lt.skautai.routes.leadershipChangeRequestRoutes
import lt.skautai.routes.organizationalUnitRoutes
import lt.skautai.services.LocationService
import lt.skautai.services.MemberService
import lt.skautai.routes.memberRoutes
import lt.skautai.routes.mobileRoutes
import lt.skautai.routes.myTaskRoutes
import lt.skautai.routes.rolesRoutes
import lt.skautai.routes.requisitionRoutes
import lt.skautai.routes.reservationRoutes
import lt.skautai.routes.uploadRoutes
import lt.skautai.routes.operationalRoutes
import lt.skautai.routes.accountDeletionRoutes
import lt.skautai.routes.publicSiteRoutes
import lt.skautai.services.ReservationService
import lt.skautai.routes.eventRoutes
import lt.skautai.routes.userRoutes
import lt.skautai.services.EventService
import java.io.File
import java.nio.file.Files
import java.util.UUID

object TestHelper {
    var lastPasswordResetLink: String? = null
    var lastAccountDeletionLink: String? = null

    fun buildTestConfig(): MapApplicationConfig {
        val config = com.typesafe.config.ConfigFactory.load("test")
        return MapApplicationConfig(
            "jwt.secret" to config.getString("test.jwt.secret"),
            "jwt.issuer" to config.getString("test.jwt.issuer"),
            "jwt.audience" to config.getString("test.jwt.audience"),
            "jwt.realm" to config.getString("test.jwt.realm"),
            "setup.bootstrapToken" to config.getString("test.setup.bootstrapToken")
        )
    }

    fun setupDatabase() {
        val config = com.typesafe.config.ConfigFactory.load("test")
        val dbUrl = config.getString("test.database.url")
        val dbUser = config.getString("test.database.user")
        val dbPassword = config.getString("test.database.password")

        Database.connect(
            url = dbUrl,
            driver = "org.postgresql.Driver",
            user = dbUser,
            password = dbPassword
        )

        transaction {
            exec("""
            DROP SCHEMA public CASCADE;
            CREATE SCHEMA public;
        """.trimIndent())

            val migrations = File("src/main/resources/db/migration")
                .listFiles { file -> file.isFile && file.name.matches(Regex("""V\d+__.*\.sql""")) }
                ?.sortedBy { file -> file.name.substringAfter('V').substringBefore("__").toInt() }
                ?: error("src/main/resources/db/migration not found")
            migrations.forEach { migration ->
                exec(migration.readText())
            }
        }
    }

    fun teardownDatabase() {
        transaction {
            exec("""
                DROP SCHEMA public CASCADE;
                CREATE SCHEMA public;
            """.trimIndent())
        }
    }

    fun cleanTables() {
        transaction {
            exec("""
                TRUNCATE TABLE
                    password_reset_tokens,
                    account_deletion_requests,
                    auth_refresh_sessions, auth_login_throttles,
                    users, tuntai, bendras_inventory_requests, super_admins,
                    leadership_change_requests, item_check_sessions,
                    reservation_movements,
                    event_purchase_items, event_purchases,
                    event_inventory_transfer_requests,
                    event_inventory_custody, event_inventory_items, event_roles,
                    user_leadership_roles, user_ranks, role_permissions,
                    roles, permissions, locations, organizational_units,
                    user_tuntas_memberships, unit_assignments,
                    invitations, inventory_kit_items, inventory_kits,
                    items, reservations, events, draugove_requisitions,
                    draugove_requisition_items
                CASCADE
            """.trimIndent())
        }
        lastPasswordResetLink = null
        lastAccountDeletionLink = null
        cleanUploadDirectories()
    }

    fun cleanUploadDirectories() {
        listOf(File("uploads/images"), File("uploads/documents")).forEach { dir ->
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    fun ApplicationTestBuilder.configureFullApp(useProductionRateLimits: Boolean = false) {
        environment {
            config = buildTestConfig()
        }
        application {
            if (useProductionRateLimits) {
                System.setProperty("REGISTRATION_RATE_LIMIT", "3")
                System.setProperty("EMAIL_REQUEST_RATE_LIMIT", "5")
            } else {
                System.setProperty("REGISTRATION_RATE_LIMIT", "100000")
                System.setProperty("EMAIL_REQUEST_RATE_LIMIT", "100000")
            }
            configureSecurity()
            configureSerialization()
            configureRateLimiting()
            configureTestRateLimitStatusPage()
            System.setProperty("PASSWORD_RESET_PUBLIC_BASE_URL", "https://api.example.test")
            System.setProperty("ACCOUNT_DELETION_PUBLIC_BASE_URL", "https://api.example.test")
            val testEmailService = object : EmailService {
                override fun sendPasswordReset(to: String, name: String, resetUrl: String): Result<Unit> {
                    lastPasswordResetLink = resetUrl
                    return Result.success(Unit)
                }

                override fun sendAccountDeletionConfirmation(
                    to: String,
                    name: String,
                    confirmationUrl: String
                ): Result<Unit> {
                    lastAccountDeletionLink = confirmationUrl
                    return Result.success(Unit)
                }
            }
            val authService = AuthService(
                environment,
                testEmailService
            )
            val accountDeletionService = AccountDeletionService(testEmailService)
            val invitationService = InvitationService()
            val itemService = ItemService()
            val itemCheckService = ItemCheckService()
            val locationService = LocationService()
            val organizationalUnitService = OrganizationalUnitService()
            val memberService = MemberService()
            val reservationService = ReservationService()
            val eventService = EventService()
            val eventPackingService = EventPackingService()
            val bendrasInventoryRequestService = BendrasInventoryRequestService()
            val requisitionService = RequisitionService()
            val inventoryTemplateService = InventoryTemplateService()
            val inventoryKitService = InventoryKitService()
            val myTaskService = MyTaskService()
            val leadershipChangeRequestService = LeadershipChangeRequestService()
            val deviceService = DeviceService()
            val notificationService = NotificationService()
            val firebaseNotificationService = FirebaseNotificationService(deviceService, notificationService)
            val notificationRecipientService = NotificationRecipientService()
            PermissionSeeder.seedPermissions()
            routing {
                operationalRoutes()
                publicSiteRoutes()
                accountDeletionRoutes(accountDeletionService)
                authRoutes(authService)
                invitationRoutes(invitationService)
                superAdminRoutes(memberService, organizationalUnitService, firebaseNotificationService, notificationRecipientService)
                itemRoutes(itemService, itemCheckService)
                locationRoutes(locationService)
                organizationalUnitRoutes(organizationalUnitService)
                memberRoutes(memberService)
                leadershipChangeRequestRoutes(leadershipChangeRequestService)
                reservationRoutes(reservationService, firebaseNotificationService, notificationRecipientService)
                eventRoutes(eventService, memberService, eventPackingService, firebaseNotificationService)
                bendrasInventoryRequestRoutes(bendrasInventoryRequestService, firebaseNotificationService, notificationRecipientService)
                requisitionRoutes(requisitionService, firebaseNotificationService, notificationRecipientService)
                inventoryTemplateRoutes(inventoryTemplateService)
                inventoryKitRoutes(inventoryKitService)
                mobileRoutes(
                    itemService,
                    reservationService,
                    bendrasInventoryRequestService,
                    requisitionService,
                    eventService,
                    organizationalUnitService,
                    myTaskService
                )
                myTaskRoutes(myTaskService)
                userRoutes()
                rolesRoutes()
                uploadRoutes()
            }
        }
    }

    suspend fun HttpClient.registerAndActivateTuntininkas(
        email: String = "tuntininkas@test.com",
        password: String = "testas123",
        tuntasName: String = "Test Tuntas"
    ): Pair<String, String> {
        val response = post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test",
                    "surname": "Tuntininkas",
                    "email": "$email",
                    "password": "$password",
                    "tuntasName": "$tuntasName",
                    "tuntasKrastas": "Vilniaus"
                }
            """.trimIndent())
        }
        check(response.status == HttpStatusCode.Created) {
            "Failed to register tuntininkas: ${response.status} ${response.bodyAsText()}"
        }

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val token = body["token"]!!.jsonPrimitive.content

        val tuntasId = transaction {
            var id = ""
            exec("SELECT id FROM tuntai WHERE name = '$tuntasName' LIMIT 1") { rs ->
                if (rs.next()) id = rs.getString("id")
            }
            exec("UPDATE tuntai SET status = 'ACTIVE' WHERE name = '$tuntasName'")
            id
        }
        check(tuntasId.isNotBlank()) {
            "Failed to resolve created tuntas id for '$tuntasName'"
        }

        return token to tuntasId
    }
    fun getRoleId(tuntasId: String, roleName: String): String {
        var id = ""
        transaction {
            exec("SELECT id FROM roles WHERE tuntas_id = '$tuntasId' AND name = '$roleName' LIMIT 1") { rs ->
                if (rs.next()) id = rs.getString("id")
            }
        }
        return id
    }

    suspend fun HttpClient.registerInvitedUser(
        inviterToken: String,
        tuntasId: String,
        roleName: String,
        email: String,
        organizationalUnitId: String? = null,
        name: String = "Test",
        surname: String = "User",
        password: String = "testas123"
    ): Pair<String, String> {
        val roleId = getRoleId(tuntasId, roleName)
        val unitField = organizationalUnitId?.let { ", \"organizationalUnitId\": \"$it\"" }.orEmpty()
        val inviteResponse = post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $inviterToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId"$unitField, "expiresInHours": 48 }""")
        }
        check(inviteResponse.status == HttpStatusCode.Created) {
            "Failed to create invitation: ${inviteResponse.status} ${inviteResponse.bodyAsText()}"
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "name": "$name",
                    "surname": "$surname",
                    "email": "$email",
                    "password": "$password",
                    "inviteCode": "$inviteCode"
                }
                """.trimIndent()
            )
        }
        check(registerResponse.status == HttpStatusCode.Created) {
            "Failed to register invited user: ${registerResponse.status} ${registerResponse.bodyAsText()}"
        }
        val body = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
        return body["token"]!!.jsonPrimitive.content to body["userId"]!!.jsonPrimitive.content
    }

    suspend fun ApplicationTestBuilder.createUnit(
        token: String,
        tuntasId: String,
        name: String,
        type: String = "SKAUTU_DRAUGOVE"
    ): String {
        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "type": "$type" }""")
        }
        check(response.status == HttpStatusCode.Created) {
            "Failed to create unit: ${response.status} ${response.bodyAsText()}"
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    fun randomEmail(prefix: String): String = "$prefix-${UUID.randomUUID()}@test.com"

    fun createTempUploadFile(name: String, bytes: ByteArray): File {
        val tempFile = Files.createTempFile("upload-", "-$name").toFile()
        tempFile.writeBytes(bytes)
        tempFile.deleteOnExit()
        return tempFile
    }

    fun multiPartForFile(
        fieldName: String = "file",
        fileName: String,
        contentType: ContentType?,
        bytes: ByteArray
    ): MultiPartFormDataContent {
        val headers = Headers.build {
            append(HttpHeaders.ContentDisposition, "form-data; name=\"$fieldName\"; filename=\"$fileName\"")
            contentType?.let { append(HttpHeaders.ContentType, it.toString()) }
        }
        return MultiPartFormDataContent(
            formData {
                append(fieldName, bytes, headers)
            }
        )
    }

}

private fun Application.configureTestRateLimitStatusPage() {
    install(StatusPages) {
        status(HttpStatusCode.TooManyRequests) { call, status ->
            val retryAfter = call.response.headers[HttpHeaders.RetryAfter]
                ?.toLongOrNull()
                ?.coerceAtLeast(1)
            val message = if (retryAfter != null) {
                "Per daug užklausų. Bandykite dar kartą po $retryAfter sek."
            } else {
                "Per daug užklausų. Palaukite ir bandykite dar kartą."
            }
            call.respond(status, lt.skautai.models.responses.ErrorResponse(message))
        }
    }
}
