package lt.skautai

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.plugins.configureSecurity
import lt.skautai.routes.authRoutes
import lt.skautai.routes.invitationRoutes
import lt.skautai.routes.itemRoutes
import lt.skautai.routes.superAdminRoutes
import lt.skautai.services.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import lt.skautai.plugins.configureSerialization
import lt.skautai.routes.bendrasInventoryRequestRoutes
import lt.skautai.routes.locationRoutes
import lt.skautai.routes.organizationalUnitRoutes
import lt.skautai.services.LocationService
import lt.skautai.services.MemberService
import lt.skautai.routes.memberRoutes
import lt.skautai.routes.requisitionRoutes
import lt.skautai.routes.reservationRoutes
import lt.skautai.services.ReservationService
import lt.skautai.routes.eventRoutes
import lt.skautai.routes.userRoutes
import lt.skautai.services.EventService

object TestHelper {

    fun buildTestConfig(): MapApplicationConfig {
        val config = com.typesafe.config.ConfigFactory.load("test")
        return MapApplicationConfig(
            "jwt.secret" to config.getString("test.jwt.secret"),
            "jwt.issuer" to config.getString("test.jwt.issuer"),
            "jwt.audience" to config.getString("test.jwt.audience"),
            "jwt.realm" to config.getString("test.jwt.realm")
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

            val schema = object {}.javaClass
                .getResource("/schema.sql")
                ?.readText()
                ?: error("schema.sql not found in resources")
            exec(schema)
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
                    users, tuntai, bendras_inventory_requests, super_admins,
                    user_leadership_roles, user_ranks, role_permissions,
                    roles, permissions, locations, organizational_units,
                    user_tuntas_memberships, unit_assignments, user_draugove_memberships,
                    invitations, items, reservations, events, draugove_requisitions,
                    draugove_requisition_items
                CASCADE
            """.trimIndent())
        }
    }

    fun ApplicationTestBuilder.configureFullApp() {
        environment {
            config = buildTestConfig()
        }
        application {
            configureSecurity()
            configureSerialization()
            val authService = AuthService(environment)
            val invitationService = InvitationService()
            val itemService = ItemService()
            val locationService = LocationService()
            val organizationalUnitService = OrganizationalUnitService()
            val memberService = MemberService()
            val reservationService = ReservationService()
            val eventService = EventService()
            val bendrasInventoryRequestService = BendrasInventoryRequestService()
            val requisitionService = RequisitionService()
            PermissionSeeder.seedPermissions()
            routing {
                authRoutes(authService)
                invitationRoutes(invitationService)
                superAdminRoutes()
                itemRoutes(itemService)
                locationRoutes(locationService)
                organizationalUnitRoutes(organizationalUnitService)
                memberRoutes(memberService)
                reservationRoutes(reservationService)
                eventRoutes(eventService)
                bendrasInventoryRequestRoutes(bendrasInventoryRequestService)
                requisitionRoutes(requisitionService)
                userRoutes()

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
}
