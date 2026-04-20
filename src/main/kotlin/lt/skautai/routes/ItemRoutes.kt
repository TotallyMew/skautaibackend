package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.CreateItemRequest
import lt.skautai.models.requests.UpdateItemRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.plugins.resolveUserPermissions
import lt.skautai.services.ItemScopeHelper
import lt.skautai.services.ItemService
import java.util.*

fun Route.itemRoutes(itemService: ItemService) {
    authenticate("auth-jwt") {
        route("/api/items") {

            get {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("items.view", tuntasUUID)) return@get

                val custodianId = call.request.queryParameters["custodianId"]
                val category = call.request.queryParameters["category"]
                val status = call.request.queryParameters["status"]

                itemService.getItems(tuntasUUID, userId, custodianId, category, status)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "Failed to fetch items")) }
            }

            get("{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!checkPermission("items.view", tuntasUUID)) return@get

                val itemId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                itemService.getItem(itemUUID, tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Item not found")) }
            }

            post {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val request = call.receive<CreateItemRequest>()

                val targetOrgUnitId = request.custodianId?.let {
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }

                val isPendingApproval: Boolean
                if (targetOrgUnitId != null) {
                    // Assigning to a specific unit — use normal scope check
                    if (!checkPermission("items.create", tuntasUUID, targetOrgUnitId)) return@post
                    isPendingApproval = false
                } else {
                    // Tuntas shared storage — only ALL-scope users allowed (draugininkas cannot create shared items)
                    val userPerms = resolveUserPermissions(userId, tuntasUUID)
                    val createPerm = userPerms.find { it.permissionName == "items.create" }
                    if (createPerm == null || createPerm.scope != "ALL") {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    }
                    isPendingApproval = false
                }

                itemService.createItem(tuntasUUID, userId, request, isPendingApproval)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create item")) }
            }

            put("{id}") {
                val principal = call.principal<JWTPrincipal>()!!

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val itemId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                // TRANSFERRED_FROM_TUNTAS items require ALL scope; pass null to block OWN_UNIT users
                val scopeInfo = ItemScopeHelper.getItemScopeInfo(itemUUID, tuntasUUID)
                val targetOrgUnitId = if (scopeInfo?.origin == "TRANSFERRED_FROM_TUNTAS") null else scopeInfo?.custodianId

                if (!checkPermission("items.update", tuntasUUID, targetOrgUnitId)) return@put

                val request = call.receive<UpdateItemRequest>()

                itemService.updateItem(itemUUID, tuntasUUID, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to update item")) }
            }

            delete("{id}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val itemId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                val deleteScopeInfo = ItemScopeHelper.getItemScopeInfo(itemUUID, tuntasUUID)
                val deleteTargetOrgUnitId = if (deleteScopeInfo?.origin == "TRANSFERRED_FROM_TUNTAS") null else deleteScopeInfo?.custodianId
                if (!checkPermission("items.delete", tuntasUUID, deleteTargetOrgUnitId)) return@delete

                itemService.deleteItem(itemUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Item deactivated")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to delete item")) }
            }
        }
    }
}