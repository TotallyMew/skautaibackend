package lt.skautai.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID
import lt.skautai.models.requests.CreateInventoryKitRequest
import lt.skautai.models.requests.UpdateInventoryKitRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.services.InventoryKitService

fun Route.inventoryKitRoutes(inventoryKitService: InventoryKitService, apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        route("$apiPrefix/inventory-kits") {
            get {
                val principal = call.principal<JWTPrincipal>()!!
                val tuntasUUID = call.tuntasIdOrRespond() ?: return@get
                if (!checkPermission("items.view", tuntasUUID, null)) return@get
                val includeInactive = call.request.queryParameters["includeInactive"]?.toBooleanStrictOrNull() ?: false
                inventoryKitService.listKits(tuntasUUID, includeInactive)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to list kits")) }
            }

            get("{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val tuntasUUID = call.tuntasIdOrRespond() ?: return@get
                if (!checkPermission("items.view", tuntasUUID, null)) return@get
                val kitUUID = call.kitIdOrRespond() ?: return@get
                inventoryKitService.getKit(kitUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Kit not found")) }
            }

            post {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasUUID = call.tuntasIdOrRespond() ?: return@post
                if (!checkPermission("items.create", tuntasUUID, null)) return@post
                val request = call.receiveValidated<CreateInventoryKitRequest>()
                inventoryKitService.createKit(tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create kit")) }
            }

            put("{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val tuntasUUID = call.tuntasIdOrRespond() ?: return@put
                if (!checkPermission("items.update", tuntasUUID, null)) return@put
                val kitUUID = call.kitIdOrRespond() ?: return@put
                val request = call.receiveValidated<UpdateInventoryKitRequest>()
                inventoryKitService.updateKit(kitUUID, tuntasUUID, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to update kit")) }
            }

            delete("{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val tuntasUUID = call.tuntasIdOrRespond() ?: return@delete
                if (!checkPermission("items.delete", tuntasUUID, null)) return@delete
                val kitUUID = call.kitIdOrRespond() ?: return@delete
                inventoryKitService.deleteKit(kitUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Inventory kit deactivated")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to delete kit")) }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.tuntasIdOrRespond(): UUID? {
    val tuntasId = request.headers["X-Tuntas-Id"]
        ?: run {
            respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
            return null
        }
    return try {
        UUID.fromString(tuntasId)
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
        null
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.kitIdOrRespond(): UUID? {
    val kitId = parameters["id"]
        ?: run {
            respond(HttpStatusCode.BadRequest, ErrorResponse("Kit ID required"))
            return null
        }
    return try {
        UUID.fromString(kitId)
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid kit ID"))
        null
    }
}
