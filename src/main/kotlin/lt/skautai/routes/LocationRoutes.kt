package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.models.requests.CreateLocationRequest
import lt.skautai.models.requests.UpdateLocationRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import lt.skautai.services.LocationService
import java.util.*

fun Route.locationRoutes(locationService: LocationService, apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        route("$apiPrefix/locations") {

            get {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val userId = try {
                    UUID.fromString(call.principal<JWTPrincipal>()!!.getClaim("userId", String::class))
                } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                }
                if (!isActiveTuntasMember(userId, tuntasUUID)) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))
                }

                locationService.getLocations(tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "Failed to fetch locations")) }
            }

            get("{id}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val locationId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Location ID required"))
                val locationUUID = try { UUID.fromString(locationId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid location ID"))
                }

                val userId = try {
                    UUID.fromString(call.principal<JWTPrincipal>()!!.getClaim("userId", String::class))
                } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                }
                if (!isActiveTuntasMember(userId, tuntasUUID)) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))
                }

                locationService.getLocation(locationUUID, tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Location not found")) }
            }

            post {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val userId = try {
                    UUID.fromString(call.principal<JWTPrincipal>()!!.getClaim("userId", String::class))
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                }
                if (!isActiveTuntasMember(userId, tuntasUUID)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))
                }

                val request = call.receiveValidated<CreateLocationRequest>()

                locationService.createLocation(tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create location")) }
            }

            put("{id}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val userId = try {
                    UUID.fromString(call.principal<JWTPrincipal>()!!.getClaim("userId", String::class))
                } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                }
                if (!isActiveTuntasMember(userId, tuntasUUID)) {
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))
                }

                val locationId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Location ID required"))
                val locationUUID = try { UUID.fromString(locationId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid location ID"))
                }

                val request = call.receiveValidated<UpdateLocationRequest>()

                locationService.updateLocation(locationUUID, tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to update location")) }
            }

            delete("{id}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val userId = try {
                    UUID.fromString(call.principal<JWTPrincipal>()!!.getClaim("userId", String::class))
                } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                }
                if (!isActiveTuntasMember(userId, tuntasUUID)) {
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))
                }

                val locationId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Location ID required"))
                val locationUUID = try { UUID.fromString(locationId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid location ID"))
                }

                locationService.deleteLocation(locationUUID, tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Location deleted")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to delete location")) }
            }
        }
    }
}

private fun isActiveTuntasMember(userId: UUID, tuntasId: UUID): Boolean = transaction {
    UserTuntasMemberships.selectAll()
        .where {
            (UserTuntasMemberships.userId eq userId) and
                (UserTuntasMemberships.tuntasId eq tuntasId) and
                UserTuntasMemberships.leftAt.isNull()
        }
        .firstOrNull() != null
}
