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
import lt.skautai.models.requests.ApplyInventoryTemplateRequest
import lt.skautai.models.requests.CreateInventoryTemplateRequest
import lt.skautai.models.requests.UpdateInventoryTemplateRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.services.InventoryTemplateService
import lt.skautai.services.PermissionContextService
import java.util.UUID

fun Route.inventoryTemplateRoutes(service: InventoryTemplateService, apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        route("$apiPrefix/inventory-templates") {
            get {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasUUID = call.tuntasIdOrRespond() ?: return@get
                if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }
                service.listTemplates(tuntasUUID, call.request.queryParameters["eventType"])
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to fetch templates")) }
            }

            post {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasUUID = call.tuntasIdOrRespond() ?: return@post
                if (!checkPermission("events.create", tuntasUUID, null)) return@post
                val request = call.receiveValidated<CreateInventoryTemplateRequest>()
                service.createTemplate(tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create template")) }
            }

            put("{id}") {
                val tuntasUUID = call.tuntasIdOrRespond() ?: return@put
                if (!checkPermission("events.create", tuntasUUID, null)) return@put
                val templateUUID = call.templateIdOrRespond() ?: return@put
                val request = call.receiveValidated<UpdateInventoryTemplateRequest>()
                service.updateTemplate(templateUUID, tuntasUUID, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to update template")) }
            }

            delete("{id}") {
                val tuntasUUID = call.tuntasIdOrRespond() ?: return@delete
                if (!checkPermission("events.create", tuntasUUID, null)) return@delete
                val templateUUID = call.templateIdOrRespond() ?: return@delete
                service.deleteTemplate(templateUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Template deleted")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to delete template")) }
            }
        }

        post("$apiPrefix/events/{id}/inventory-plan/from-template") {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = UUID.fromString(principal.getClaim("userId", String::class))
            val tuntasUUID = call.tuntasIdOrRespond() ?: return@post
            if (!checkPermission("events.manage", tuntasUUID, null)) return@post
            val eventUUID = call.eventIdOrRespond() ?: return@post
            val request = call.receiveValidated<ApplyInventoryTemplateRequest>()
            val templateUUID = try {
                UUID.fromString(request.templateId)
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid template ID"))
            }
            service.applyTemplateToEvent(eventUUID, tuntasUUID, userId, templateUUID)
                .onSuccess { call.respond(HttpStatusCode.Created, it) }
                .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to apply template")) }
        }

        post("$apiPrefix/events/{id}/apply-template-with-reservation") {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = UUID.fromString(principal.getClaim("userId", String::class))
            val tuntasUUID = call.tuntasIdOrRespond() ?: return@post
            if (!checkPermission("events.manage", tuntasUUID, null)) return@post
            val eventUUID = call.eventIdOrRespond() ?: return@post
            val request = call.receiveValidated<ApplyInventoryTemplateRequest>()
            val templateUUID = try {
                UUID.fromString(request.templateId)
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid template ID"))
            }
            service.applyTemplateWithReservation(eventUUID, tuntasUUID, userId, templateUUID)
                .onSuccess { call.respond(HttpStatusCode.Created, it) }
                .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to apply template")) }
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

private suspend fun io.ktor.server.application.ApplicationCall.templateIdOrRespond(): UUID? {
    val id = parameters["id"]
        ?: run {
            respond(HttpStatusCode.BadRequest, ErrorResponse("Template ID required"))
            return null
        }
    return try {
        UUID.fromString(id)
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid template ID"))
        null
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.eventIdOrRespond(): UUID? {
    val id = parameters["id"]
        ?: run {
            respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
            return null
        }
    return try {
        UUID.fromString(id)
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
        null
    }
}
