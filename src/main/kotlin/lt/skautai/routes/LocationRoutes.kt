package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.CreateLocationRequest
import lt.skautai.models.requests.UpdateLocationRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.services.LocationService
import java.util.*

fun Route.locationRoutes(locationService: LocationService) {
    authenticate("auth-jwt") {
        route("/api/locations") {

            get {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("items.view", tuntasUUID)) return@get

                val userId = try {
                    UUID.fromString(call.principal<JWTPrincipal>()!!.getClaim("userId", String::class))
                } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
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

                if (!checkPermission("items.view", tuntasUUID)) return@get

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

                if (!checkPermission("items.view", tuntasUUID)) return@post

                val userId = try {
                    UUID.fromString(call.principal<JWTPrincipal>()!!.getClaim("userId", String::class))
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                }

                val request = call.receive<CreateLocationRequest>()

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

                if (!checkPermission("items.view", tuntasUUID)) return@put

                val userId = try {
                    UUID.fromString(call.principal<JWTPrincipal>()!!.getClaim("userId", String::class))
                } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                }

                val locationId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Location ID required"))
                val locationUUID = try { UUID.fromString(locationId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid location ID"))
                }

                val request = call.receive<UpdateLocationRequest>()

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

                if (!checkPermission("items.view", tuntasUUID)) return@delete

                val userId = try {
                    UUID.fromString(call.principal<JWTPrincipal>()!!.getClaim("userId", String::class))
                } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
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
