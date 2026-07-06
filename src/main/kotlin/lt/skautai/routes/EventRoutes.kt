package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.*
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.EventInventoryRequestResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.plugins.resolveUserPermissions
import lt.skautai.services.EventService
import lt.skautai.services.EventPackingService
import lt.skautai.services.FirebaseNotificationService
import lt.skautai.services.MemberService
import lt.skautai.util.UploadStorage
import java.util.*

private fun notifyInventoryRequestAssignment(
    firebaseNotificationService: FirebaseNotificationService,
    tuntasId: UUID,
    response: EventInventoryRequestResponse,
    assignmentChanged: Boolean
) {
    if (!assignmentChanged) return
    val responsibleUserId = response.responsibleUserId
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: return
    firebaseNotificationService.sendToUser(
        userId = responsibleUserId,
        title = "Priskirtas renginio poreikis",
        body = buildString {
            append(response.itemName)
            response.pastovykleName?.let { append(" (${it})") }
            response.dueAt?.take(10)?.let { append(", terminas $it") }
        },
        data = mapOf(
            "resource" to "event_inventory_request",
            "entityId" to response.id,
            "requestId" to response.id,
            "eventId" to response.eventId,
            "tuntasId" to tuntasId.toString()
        )
    )
}

fun Route.eventRoutes(
    eventService: EventService,
    memberService: MemberService,
    eventPackingService: EventPackingService,
    firebaseNotificationService: FirebaseNotificationService,
    apiPrefix: String = "/api"
) {
    authenticate("auth-jwt") {
        route("$apiPrefix/events") {

            get {
                val userId = currentUserId() ?: return@get
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val type = call.request.queryParameters["type"]
                val status = call.request.queryParameters["status"]
                val updatedAfter = call.request.queryParameters["updatedAfter"]?.let(::parseInstantOrNull)
                val limit = call.request.queryParameters["limit"]?.let { raw ->
                    raw.toIntOrNull()?.takeIf { it in 1..200 }
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("limit must be between 1 and 200"))
                }
                val offset = call.request.queryParameters["offset"]?.let { raw ->
                    raw.toIntOrNull()?.takeIf { it >= 0 }
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("offset must be zero or greater"))
                } ?: 0

                val result = when {
                    eventService.canViewEvents(userId, tuntasUUID) ->
                        eventService.getVisibleEvents(tuntasUUID, userId, type, status, updatedAfter, limit, offset)
                    eventService.hasResponsiblePastovykle(userId, tuntasUUID) ->
                        eventService.getResponsibleEvents(tuntasUUID, userId, type, status, updatedAfter, limit, offset)
                    else -> {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                        return@get
                    }
                }

                result
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "Failed to fetch events")) }
            }

            get("{id}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val eventId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                }
                if (!canViewEvent(eventService, tuntasUUID, eventUUID)) return@get

                eventService.getEvent(eventUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Event not found")) }
            }

            get("{id}/candidate-members") {
                val userId = currentUserId() ?: return@get
                val tuntasUUID = parseTuntasId() ?: return@get
                val eventUUID = parseEventId() ?: return@get
                val canViewCandidates =
                    eventService.canManageEvent(eventUUID, tuntasUUID, userId) ||
                        eventService.canManageEventInventory(eventUUID, tuntasUUID, userId) ||
                        eventService.hasResponsiblePastovykleForEvent(userId, tuntasUUID, eventUUID)
                if (!canViewCandidates) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                memberService.getEventCandidateMembers(tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { e ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(e.message ?: "Failed to fetch candidate members")
                        )
                    }
            }

            post {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val request = call.receiveValidated<CreateEventRequest>()
                val targetOrgUnitId = request.organizationalUnitId?.let {
                    try { UUID.fromString(it) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid organizational unit ID"))
                    }
                }

                if (!eventService.canCreateEvent(userId, tuntasUUID, targetOrgUnitId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                eventService.createEvent(tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create event")) }
            }

            put("{id}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val eventId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                }
                val request = call.receiveValidated<UpdateEventRequest>()
                if (request.status == "ACTIVE") {
                    if (!canStartEvent(eventService, tuntasUUID, eventUUID)) return@put
                } else {
                    if (!canManageEvent(eventService, tuntasUUID, eventUUID)) return@put
                }

                eventService.updateEvent(eventUUID, tuntasUUID, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to update event")) }
            }

            delete("{id}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val eventId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                }
                if (!canManageEvent(eventService, tuntasUUID, eventUUID)) return@delete

                eventService.deleteEvent(eventUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Event cancelled")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to cancel event")) }
            }

            route("{id}/roles") {

                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))

                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    val eventId = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                    val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                    }
                    if (!canManageEvent(eventService, tuntasUUID, eventUUID)) return@post

                    val request = call.receiveValidated<AssignEventRoleRequest>()

                    eventService.assignEventRole(eventUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to assign event role")) }
                }

                delete("{roleId}") {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    val eventId = call.parameters["id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                    val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                    }
                    if (!canManageEvent(eventService, tuntasUUID, eventUUID)) return@delete

                    val roleId = call.parameters["roleId"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Role ID required"))
                    val roleUUID = try { UUID.fromString(roleId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid role ID"))
                    }

                    eventService.removeEventRole(eventUUID, roleUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.NoContent) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to remove event role")) }
                }
            }

            route("{id}/inventory-plan") {
                get {
                    val tuntasUUID = parseTuntasId() ?: return@get
                    val eventUUID = parseEventId() ?: return@get
                    if (!canViewEventInventory(eventService, tuntasUUID, eventUUID)) return@get
                    eventService.getEventInventoryPlan(eventUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to fetch inventory plan")) }
                }
            }

            route("{id}/packing-list") {
                get {
                    val tuntasUUID = parseTuntasId() ?: return@get
                    val eventUUID = parseEventId() ?: return@get
                    if (!canViewEventInventory(eventService, tuntasUUID, eventUUID)) return@get
                    eventPackingService.getPackingList(eventUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to fetch packing list")) }
                }

                post("generate") {
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    eventPackingService.generateFromPlan(eventUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to generate packing list")) }
                }

                post("containers") {
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val request = call.receiveValidated<CreateEventPackingContainerRequest>()
                    eventPackingService.createContainer(eventUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to create packing container")) }
                }

                put("lines/{lineId}") {
                    val userId = currentUserId() ?: return@put
                    val tuntasUUID = parseTuntasId() ?: return@put
                    val eventUUID = parseEventId() ?: return@put
                    val lineUUID = parseUuidParameter("lineId", "Invalid packing line ID") ?: return@put
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@put
                    val request = call.receiveValidated<UpdateEventPackingLineRequest>()
                    eventPackingService.updateLine(eventUUID, lineUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to update packing line")) }
                }
            }

            route("{id}/inventory-requests") {
                get {
                    val userId = currentUserId() ?: return@get
                    val tuntasUUID = parseTuntasId() ?: return@get
                    val eventUUID = parseEventId() ?: return@get
                    if (!canViewEventInventory(eventService, tuntasUUID, eventUUID)) return@get
                    val includeAll = canManageEventInventory(eventService, tuntasUUID, eventUUID)
                    eventService.getEventInventoryRequests(eventUUID, tuntasUUID, userId, includeAll)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e ->
                            val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                            call.respond(status, ErrorResponse(e.message ?: "Failed to fetch inventory requests"))
                        }
                }

                post {
                    val userId = currentUserId() ?: return@post
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!eventService.canRequestEventInventory(eventUUID, tuntasUUID, userId)) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    }
                    val request = call.receiveValidated<CreateEventInventoryRequestRequest>()
                    eventService.createEventInventoryRequest(eventUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e ->
                            val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                            call.respond(status, ErrorResponse(e.message ?: "Failed to create inventory request"))
                        }
                }

                put("{requestId}") {
                    val userId = currentUserId() ?: return@put
                    val tuntasUUID = parseTuntasId() ?: return@put
                    val eventUUID = parseEventId() ?: return@put
                    val requestUUID = parseUuidParameter("requestId", "Invalid request ID") ?: return@put
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@put
                    val request = call.receiveValidated<UpdateEventInventoryRequestRequest>()
                    eventService.updateInventoryRequest(eventUUID, requestUUID, tuntasUUID, userId, request)
                        .onSuccess {
                            notifyInventoryRequestAssignment(firebaseNotificationService, tuntasUUID, it, request.responsibleUserId != null)
                            call.respond(HttpStatusCode.OK, it)
                        }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to update inventory request")) }
                }
            }

            get("{id}/inventory-readiness") {
                val tuntasUUID = parseTuntasId() ?: return@get
                val eventUUID = parseEventId() ?: return@get
                if (!canViewEventInventory(eventService, tuntasUUID, eventUUID)) return@get
                eventService.getInventoryReadiness(eventUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to fetch inventory readiness")) }
            }

            route("{id}/inventory-buckets") {
                post {
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val request = call.receiveValidated<CreateEventInventoryBucketRequest>()
                    eventService.createInventoryBucket(eventUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to create inventory bucket")) }
                }

                put("{bucketId}") {
                    val tuntasUUID = parseTuntasId() ?: return@put
                    val eventUUID = parseEventId() ?: return@put
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@put
                    val bucketUUID = parseUuidParameter("bucketId", "Invalid bucket ID") ?: return@put
                    val request = call.receiveValidated<UpdateEventInventoryBucketRequest>()
                    eventService.updateInventoryBucket(eventUUID, bucketUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to update inventory bucket")) }
                }

                delete("{bucketId}") {
                    val tuntasUUID = parseTuntasId() ?: return@delete
                    val eventUUID = parseEventId() ?: return@delete
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@delete
                    val bucketUUID = parseUuidParameter("bucketId", "Invalid bucket ID") ?: return@delete
                    eventService.deleteInventoryBucket(eventUUID, bucketUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Inventory bucket deleted")) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to delete inventory bucket")) }
                }
            }

            route("{id}/inventory-items") {
                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val request = call.receiveValidated<CreateEventInventoryItemRequest>()
                    eventService.createInventoryItem(eventUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to create inventory item")) }
                }

                post("bulk") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val request = call.receiveValidated<CreateEventInventoryItemsBulkRequest>()
                    eventService.createInventoryItemsBulk(eventUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to create inventory items")) }
                }

                put("{inventoryItemId}") {
                    val tuntasUUID = parseTuntasId() ?: return@put
                    val eventUUID = parseEventId() ?: return@put
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@put
                    val inventoryItemUUID = parseUuidParameter("inventoryItemId", "Invalid inventory item ID") ?: return@put
                    val request = call.receiveValidated<UpdateEventInventoryItemRequest>()
                    eventService.updateInventoryItem(eventUUID, inventoryItemUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to update inventory item")) }
                }

                delete("{inventoryItemId}") {
                    val tuntasUUID = parseTuntasId() ?: return@delete
                    val eventUUID = parseEventId() ?: return@delete
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@delete
                    val inventoryItemUUID = parseUuidParameter("inventoryItemId", "Invalid inventory item ID") ?: return@delete
                    eventService.deleteInventoryItem(eventUUID, inventoryItemUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Inventory item deleted")) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to delete inventory item")) }
                }

                post("{inventoryItemId}/sources") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val inventoryItemUUID = parseUuidParameter("inventoryItemId", "Invalid inventory item ID") ?: return@post
                    val request = call.receiveValidated<CreateEventInventorySourceRequest>()
                    eventService.createInventorySource(eventUUID, inventoryItemUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to create inventory source")) }
                }

                put("sources/{sourceId}") {
                    val tuntasUUID = parseTuntasId() ?: return@put
                    val eventUUID = parseEventId() ?: return@put
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@put
                    val sourceUUID = parseUuidParameter("sourceId", "Invalid inventory source ID") ?: return@put
                    val request = call.receiveValidated<UpdateEventInventorySourceRequest>()
                    eventService.updateInventorySource(eventUUID, sourceUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to update inventory source")) }
                }

                delete("sources/{sourceId}") {
                    val tuntasUUID = parseTuntasId() ?: return@delete
                    val eventUUID = parseEventId() ?: return@delete
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@delete
                    val sourceUUID = parseUuidParameter("sourceId", "Invalid inventory source ID") ?: return@delete
                    eventService.deleteInventorySource(eventUUID, sourceUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Inventory source deleted")) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to delete inventory source")) }
                }
            }

            route("{id}/inventory-allocations") {
                post {
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val request = call.receiveValidated<CreateEventInventoryAllocationRequest>()
                    eventService.createInventoryAllocation(eventUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to create inventory allocation")) }
                }

                put("{allocationId}") {
                    val tuntasUUID = parseTuntasId() ?: return@put
                    val eventUUID = parseEventId() ?: return@put
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@put
                    val allocationUUID = parseUuidParameter("allocationId", "Invalid allocation ID") ?: return@put
                    val request = call.receiveValidated<UpdateEventInventoryAllocationRequest>()
                    eventService.updateInventoryAllocation(eventUUID, allocationUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to update inventory allocation")) }
                }

                delete("{allocationId}") {
                    val tuntasUUID = parseTuntasId() ?: return@delete
                    val eventUUID = parseEventId() ?: return@delete
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@delete
                    val allocationUUID = parseUuidParameter("allocationId", "Invalid allocation ID") ?: return@delete
                    eventService.deleteInventoryAllocation(eventUUID, allocationUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Inventory allocation deleted")) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to delete inventory allocation")) }
                }
            }

            route("{id}/inventory-custody") {
                get {
                    val tuntasUUID = parseTuntasId() ?: return@get
                    val eventUUID = parseEventId() ?: return@get
                    if (!canViewEvent(eventService, tuntasUUID, eventUUID)) return@get
                    eventService.getInventoryCustody(eventUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to fetch inventory custody")) }
                }
            }

            route("{id}/inventory-movements") {
                get {
                    val tuntasUUID = parseTuntasId() ?: return@get
                    val eventUUID = parseEventId() ?: return@get
                    if (!canViewEvent(eventService, tuntasUUID, eventUUID)) return@get
                    eventService.getInventoryMovements(eventUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to fetch inventory movements")) }
                }

                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!eventService.isTuntasMember(userId, tuntasUUID)) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))
                    }
                    val userPermissions = resolveUserPermissions(userId, tuntasUUID)
                    val canManageInventory = eventService.canManageEventInventory(eventUUID, tuntasUUID, userId) ||
                        userPermissions.any {
                            it.permissionName == "events.inventory.distribute" && it.scope == "ALL"
                        }
                    val request = call.receiveValidated<CreateEventInventoryMovementRequest>()
                    eventService.createInventoryMovement(eventUUID, tuntasUUID, userId, request, canManageInventory)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e ->
                            val message = e.message ?: "Failed to create inventory movement"
                            val status = if (message == "Insufficient permissions") HttpStatusCode.Forbidden else HttpStatusCode.BadRequest
                            call.respond(status, ErrorResponse(message))
                        }
                }
            }

            route("{id}/inventory-transfer-requests") {
                get {
                    val userId = currentUserId() ?: return@get
                    val tuntasUUID = parseTuntasId() ?: return@get
                    val eventUUID = parseEventId() ?: return@get
                    if (!canViewEvent(eventService, tuntasUUID, eventUUID)) return@get
                    val includeAll = eventService.canManageEventInventory(eventUUID, tuntasUUID, userId)
                    eventService.getInventoryTransferRequests(eventUUID, tuntasUUID, userId, includeAll)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e ->
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(e.message ?: "Failed to fetch transfer requests")
                            )
                        }
                }

                post {
                    val userId = currentUserId() ?: return@post
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!eventService.isTuntasMember(userId, tuntasUUID)) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))
                    }
                    val request = call.receiveValidated<CreateEventInventoryTransferRequest>()
                    eventService.createInventoryTransferRequest(eventUUID, tuntasUUID, userId, request)
                        .onSuccess { response ->
                            firebaseNotificationService.sendToUser(
                                userId = UUID.fromString(response.requestedFromUserId),
                                title = "Inventoriaus perdavimo prašymas",
                                body = "${response.requestedByUserName ?: "Renginio dalyvis"} prašo perduoti ${response.itemName} (${response.quantity}).",
                                data = mapOf(
                                    "resource" to "event_inventory_transfer_request",
                                    "entityId" to response.id,
                                    "eventId" to response.eventId,
                                    "tuntasId" to tuntasUUID.toString()
                                )
                            )
                            call.respond(HttpStatusCode.Created, response)
                        }
                        .onFailure { e ->
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(e.message ?: "Failed to create transfer request")
                            )
                        }
                }

                post("{requestId}/respond") {
                    val userId = currentUserId() ?: return@post
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    val requestUUID = parseUuidParameter("requestId", "Invalid transfer request ID") ?: return@post
                    val canManageInventory = eventService.canManageEventInventory(eventUUID, tuntasUUID, userId)
                    val request = call.receiveValidated<RespondEventInventoryTransferRequest>()
                    eventService.respondToInventoryTransferRequest(
                        eventUUID,
                        requestUUID,
                        tuntasUUID,
                        userId,
                        canManageInventory,
                        request
                    )
                        .onSuccess { response ->
                            firebaseNotificationService.sendToUser(
                                userId = UUID.fromString(response.requestedByUserId),
                                title = if (request.approve) "Inventorius perduotas" else "Perdavimo prašymas atmestas",
                                body = if (request.approve) {
                                    "${response.requestedFromUserName ?: "Turėtojas"} perdavė ${response.itemName} (${response.quantity})."
                                } else {
                                    "${response.requestedFromUserName ?: "Turėtojas"} atmetė prašymą dėl ${response.itemName}."
                                },
                                data = mapOf(
                                    "resource" to "event_inventory_transfer_request",
                                    "entityId" to response.id,
                                    "eventId" to response.eventId,
                                    "tuntasId" to tuntasUUID.toString()
                                )
                            )
                            call.respond(HttpStatusCode.OK, response)
                        }
                        .onFailure { e ->
                            val message = e.message ?: "Failed to respond to transfer request"
                            val status = if ("Only the current holder" in message) {
                                HttpStatusCode.Forbidden
                            } else {
                                HttpStatusCode.BadRequest
                            }
                            call.respond(status, ErrorResponse(message))
                        }
                }
            }

            route("{id}/purchases") {
                get {
                    val tuntasUUID = parseTuntasId() ?: return@get
                    val eventUUID = parseEventId() ?: return@get
                    if (!canManageEventFinance(eventService, tuntasUUID, eventUUID)) return@get
                    val limit = call.request.queryParameters["limit"]?.let { raw ->
                        raw.toIntOrNull()?.takeIf { it in 1..200 }
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("limit must be between 1 and 200"))
                    }
                    val offset = call.request.queryParameters["offset"]?.let { raw ->
                        raw.toIntOrNull()?.takeIf { it >= 0 }
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("offset must be zero or greater"))
                    } ?: 0
                    eventService.getPurchases(eventUUID, tuntasUUID, limit, offset)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to fetch purchases")) }
                }

                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventFinance(eventService, tuntasUUID, eventUUID)) return@post
                    val request = call.receiveValidated<CreateEventPurchaseRequest>()
                    eventService.createPurchase(eventUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to create purchase")) }
                }

                put("{purchaseId}") {
                    val tuntasUUID = parseTuntasId() ?: return@put
                    val eventUUID = parseEventId() ?: return@put
                    if (!canManageEventFinance(eventService, tuntasUUID, eventUUID)) return@put
                    val purchaseUUID = parseUuidParameter("purchaseId", "Invalid purchase ID") ?: return@put
                    val request = call.receiveValidated<UpdateEventPurchaseRequest>()
                    eventService.updatePurchase(eventUUID, purchaseUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to update purchase")) }
                }

                post("{purchaseId}/invoice") {
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventFinance(eventService, tuntasUUID, eventUUID)) return@post
                    val purchaseUUID = parseUuidParameter("purchaseId", "Invalid purchase ID") ?: return@post
                    val request = call.receiveValidated<AttachEventPurchaseInvoiceRequest>()
                    eventService.attachPurchaseInvoice(eventUUID, purchaseUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to attach invoice")) }
                }

                get("{purchaseId}/invoice/download") {
                    val tuntasUUID = parseTuntasId() ?: return@get
                    val eventUUID = parseEventId() ?: return@get
                    val purchaseUUID = parseUuidParameter("purchaseId", "Invalid purchase ID") ?: return@get
                    if (!canDownloadPurchaseInvoice(eventService, tuntasUUID, eventUUID)) return@get

                    eventService.getPurchaseInvoiceFileName(eventUUID, purchaseUUID, tuntasUUID)
                        .onSuccess { fileName ->
                            val file = UploadStorage.resolveDocument(fileName)
                                ?: return@onSuccess call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid invoice file name"))
                            if (!file.exists()) {
                                return@onSuccess call.respond(HttpStatusCode.NotFound, ErrorResponse("Invoice file not found"))
                            }
                            call.response.header(
                                HttpHeaders.ContentDisposition,
                                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
                            )
                            call.respondFile(file)
                        }
                        .onFailure { e ->
                            val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                            call.respond(status, ErrorResponse(e.message ?: "Failed to download invoice"))
                        }
                }

                get("{purchaseId}/invoices/{invoiceId}/download") {
                    val tuntasUUID = parseTuntasId() ?: return@get
                    val eventUUID = parseEventId() ?: return@get
                    val purchaseUUID = parseUuidParameter("purchaseId", "Invalid purchase ID") ?: return@get
                    val invoiceUUID = parseUuidParameter("invoiceId", "Invalid invoice ID") ?: return@get
                    if (!canDownloadPurchaseInvoice(eventService, tuntasUUID, eventUUID)) return@get

                    eventService.getPurchaseInvoiceFileName(eventUUID, purchaseUUID, tuntasUUID, invoiceUUID)
                        .onSuccess { fileName ->
                            val file = UploadStorage.resolveDocument(fileName)
                                ?: return@onSuccess call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid invoice file name"))
                            if (!file.exists()) {
                                return@onSuccess call.respond(HttpStatusCode.NotFound, ErrorResponse("Invoice file not found"))
                            }
                            call.response.header(
                                HttpHeaders.ContentDisposition,
                                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
                            )
                            call.respondFile(file)
                        }
                        .onFailure { e ->
                            val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                            call.respond(status, ErrorResponse(e.message ?: "Failed to download invoice"))
                        }
                }

                post("{purchaseId}/complete") {
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventFinance(eventService, tuntasUUID, eventUUID)) return@post
                    val purchaseUUID = parseUuidParameter("purchaseId", "Invalid purchase ID") ?: return@post
                    eventService.completePurchase(eventUUID, purchaseUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to complete purchase")) }
                }

                post("{purchaseId}/add-to-inventory") {
                    call.respond(
                        HttpStatusCode.Gone,
                        ErrorResponse("Pirkinių pridėjimas į inventorių perkeltas į renginio inventoriaus suvedimą")
                    )
                }
            }

            route("{id}/finance") {
                get {
                    val tuntasUUID = parseTuntasId() ?: return@get
                    val eventUUID = parseEventId() ?: return@get
                    if (!canManageEventFinance(eventService, tuntasUUID, eventUUID)) return@get
                    eventService.getEventFinance(eventUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to fetch event finance")) }
                }

                put("budget") {
                    val tuntasUUID = parseTuntasId() ?: return@put
                    val eventUUID = parseEventId() ?: return@put
                    if (!canManageEventFinance(eventService, tuntasUUID, eventUUID)) return@put
                    val request = call.receiveValidated<UpdateEventFinanceBudgetRequest>()
                    eventService.updateEventFinanceBudget(eventUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to update event budget")) }
                }

                post("costs") {
                    val userId = currentUserId() ?: return@post
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventFinance(eventService, tuntasUUID, eventUUID)) return@post
                    val request = call.receiveValidated<CreateEventExtraCostRequest>()
                    eventService.createEventExtraCost(eventUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to create event cost")) }
                }

                put("costs/{costId}") {
                    val tuntasUUID = parseTuntasId() ?: return@put
                    val eventUUID = parseEventId() ?: return@put
                    val costUUID = parseUuidParameter("costId", "Invalid extra cost ID") ?: return@put
                    if (!canManageEventFinance(eventService, tuntasUUID, eventUUID)) return@put
                    val request = call.receiveValidated<UpdateEventExtraCostRequest>()
                    eventService.updateEventExtraCost(eventUUID, tuntasUUID, costUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to update event cost")) }
                }

                delete("costs/{costId}") {
                    val tuntasUUID = parseTuntasId() ?: return@delete
                    val eventUUID = parseEventId() ?: return@delete
                    val costUUID = parseUuidParameter("costId", "Invalid extra cost ID") ?: return@delete
                    if (!canManageEventFinance(eventService, tuntasUUID, eventUUID)) return@delete
                    eventService.deleteEventExtraCost(eventUUID, tuntasUUID, costUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to delete event cost")) }
                }
            }

            route("{id}/reconciliation") {
                get {
                    val tuntasUUID = parseTuntasId() ?: return@get
                    val eventUUID = parseEventId() ?: return@get
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@get
                    eventService.getReconciliation(eventUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to fetch reconciliation")) }
                }

                post("returns") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val request = call.receiveValidated<ReconcileEventReturnsRequest>()
                    eventService.reconcileReturns(eventUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to reconcile returns")) }
                }

                post("purchases") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val request = call.receiveValidated<ReconcileEventPurchasesRequest>()
                    eventService.reconcilePurchases(eventUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to reconcile purchases")) }
                }

                get("purchases/{purchaseItemId}/candidates") {
                    val tuntasUUID = parseTuntasId() ?: return@get
                    val eventUUID = parseEventId() ?: return@get
                    val purchaseItemUUID = parseUuidParameter("purchaseItemId", "Invalid purchase item ID") ?: return@get
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@get

                    eventService.getPurchaseReconciliationCandidates(eventUUID, tuntasUUID, purchaseItemUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to fetch purchase candidates")) }
                }
            }

            post("{id}/complete") {
                val tuntasUUID = parseTuntasId() ?: return@post
                val eventUUID = parseEventId() ?: return@post
                if (!canManageEvent(eventService, tuntasUUID, eventUUID)) return@post
                eventService.completeEvent(eventUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to complete event")) }
            }

            route("{id}/pastovykles") {

                get {
                    val userId = currentUserId() ?: return@get
                    val tuntasUUID = parseTuntasId() ?: return@get

                    val eventUUID = parseEventId() ?: return@get
                    if (!canViewEvent(eventService, tuntasUUID, eventUUID)) return@get

                    val canSeeAllPastovykles =
                        eventService.canManageEvent(eventUUID, tuntasUUID, userId) ||
                            eventService.canManageEventInventory(eventUUID, tuntasUUID, userId)
                    val result = if (canSeeAllPastovykles) {
                        eventService.getPastovykles(eventUUID, tuntasUUID)
                    } else {
                        eventService.getResponsiblePastovykles(eventUUID, tuntasUUID, userId)
                    }

                    result
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to fetch pastovyklės")) }
                }

                post {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    val eventId = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                    val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                    }
                    if (!canManageEvent(eventService, tuntasUUID, eventUUID)) return@post

                    val request = call.receiveValidated<CreatePastovykleRequest>()

                    eventService.createPastovykle(eventUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create pastovyklė")) }
                }

                get("{pid}") {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    val eventId = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                    val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                    }
                    if (!canViewEvent(eventService, tuntasUUID, eventUUID)) return@get

                    val pid = call.parameters["pid"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Pastovyklė ID required"))
                    val pidUUID = try { UUID.fromString(pid) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid pastovyklė ID"))
                    }

                    if (!canAccessPastovykle(eventService, tuntasUUID, eventUUID, pidUUID)) return@get

                    eventService.getPastovykle(eventUUID, pidUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e ->
                            val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                            call.respond(status, ErrorResponse(e.message ?: "Pastovyklė not found"))
                        }
                }

                put("{pid}") {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    val eventId = call.parameters["id"]
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                    val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                    }
                    if (!canManageEvent(eventService, tuntasUUID, eventUUID)) return@put

                    val pid = call.parameters["pid"]
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Pastovyklė ID required"))
                    val pidUUID = try { UUID.fromString(pid) } catch (e: Exception) {
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid pastovyklė ID"))
                    }

                    val request = call.receiveValidated<UpdatePastovykleRequest>()

                    eventService.updatePastovykle(eventUUID, pidUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e ->
                            val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                            call.respond(status, ErrorResponse(e.message ?: "Failed to update pastovyklė"))
                        }
                }

                delete("{pid}") {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    val eventId = call.parameters["id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                    val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                    }
                    if (!canManageEvent(eventService, tuntasUUID, eventUUID)) return@delete

                    val pid = call.parameters["pid"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Pastovyklė ID required"))
                    val pidUUID = try { UUID.fromString(pid) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid pastovyklė ID"))
                    }

                    eventService.deletePastovykle(eventUUID, pidUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Pastovykle deleted")) }
                        .onFailure { e ->
                            val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                            call.respond(status, ErrorResponse(e.message ?: "Failed to delete pastovyklė"))
                        }
                }

                route("{pid}/leaders") {
                    post {
                        val userId = currentUserId() ?: return@post
                        val tuntasUUID = parseTuntasId() ?: return@post
                        val eventUUID = parseEventId() ?: return@post
                        val pastovykleUUID = parseUuidParameter("pid", "Invalid pastovyklÄ— ID") ?: return@post
                        if (!eventService.canManageEvent(eventUUID, tuntasUUID, userId)) {
                            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                        }

                        val request = call.receiveValidated<AssignPastovykleLeaderRequest>()
                        eventService.assignPastovykleLeader(eventUUID, pastovykleUUID, tuntasUUID, userId, request)
                            .onSuccess { call.respond(HttpStatusCode.Created, it) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to assign pastovyklÄ— leader"))
                            }
                    }

                    delete("{roleId}") {
                        val userId = currentUserId() ?: return@delete
                        val tuntasUUID = parseTuntasId() ?: return@delete
                        val eventUUID = parseEventId() ?: return@delete
                        val pastovykleUUID = parseUuidParameter("pid", "Invalid pastovyklÄ— ID") ?: return@delete
                        val roleUUID = parseUuidParameter("roleId", "Invalid role ID") ?: return@delete
                        if (!eventService.canManageEvent(eventUUID, tuntasUUID, userId)) {
                            return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                        }

                        eventService.removePastovykleLeader(eventUUID, pastovykleUUID, roleUUID, tuntasUUID)
                            .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("PastovyklÄ—s bendravadovis paÅ¡alintas")) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to remove pastovyklÄ— leader"))
                            }
                    }
                }

                route("{pid}/members") {
                    get {
                        val tuntasUUID = parseTuntasId() ?: return@get
                        val eventUUID = parseEventId() ?: return@get
                        val pastovykleUUID = parseUuidParameter("pid", "Invalid pastovyklė ID") ?: return@get
                        if (!canAccessPastovykle(eventService, tuntasUUID, eventUUID, pastovykleUUID)) return@get

                        eventService.getPastovykleMembers(eventUUID, pastovykleUUID, tuntasUUID)
                            .onSuccess { call.respond(HttpStatusCode.OK, it) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to fetch pastovyklė members"))
                            }
                    }

                    post {
                        val userId = currentUserId() ?: return@post
                        val tuntasUUID = parseTuntasId() ?: return@post
                        val eventUUID = parseEventId() ?: return@post
                        val pastovykleUUID = parseUuidParameter("pid", "Invalid pastovyklė ID") ?: return@post
                        if (!canAccessPastovykle(eventService, tuntasUUID, eventUUID, pastovykleUUID)) return@post

                        val request = call.receiveValidated<AddPastovykleMemberRequest>()
                        eventService.addPastovykleMember(eventUUID, pastovykleUUID, tuntasUUID, userId, request)
                            .onSuccess { call.respond(HttpStatusCode.Created, it) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to add pastovyklė member"))
                            }
                    }

                    delete("{memberId}") {
                        val tuntasUUID = parseTuntasId() ?: return@delete
                        val eventUUID = parseEventId() ?: return@delete
                        val pastovykleUUID = parseUuidParameter("pid", "Invalid pastovyklė ID") ?: return@delete
                        val memberUUID = parseUuidParameter("memberId", "Invalid member ID") ?: return@delete
                        if (!canAccessPastovykle(eventService, tuntasUUID, eventUUID, pastovykleUUID)) return@delete

                        eventService.removePastovykleMember(eventUUID, pastovykleUUID, memberUUID, tuntasUUID)
                            .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Pastovyklės narys pašalintas")) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase() || "nerastas" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to remove pastovyklė member"))
                            }
                    }
                }

                route("{pid}/inventory") {

                    get {
                        val tuntasId = call.request.headers["X-Tuntas-Id"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                        val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                            return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                        }

                        val eventId = call.parameters["id"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                        val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                            return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                        }
                        if (!canViewEvent(eventService, tuntasUUID, eventUUID)) return@get

                        val pid = call.parameters["pid"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Pastovyklė ID required"))
                        val pidUUID = try { UUID.fromString(pid) } catch (e: Exception) {
                            return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid pastovyklė ID"))
                        }

                        if (!canAccessPastovykle(eventService, tuntasUUID, eventUUID, pidUUID)) return@get

                        eventService.getPastovykleInventory(eventUUID, pidUUID, tuntasUUID)
                            .onSuccess { call.respond(HttpStatusCode.OK, it) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to fetch inventory"))
                            }
                    }

                    post {
                        val principal = call.principal<JWTPrincipal>()!!
                        val userId = UUID.fromString(principal.getClaim("userId", String::class))

                        val tuntasId = call.request.headers["X-Tuntas-Id"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                        val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                        }

                        val eventId = call.parameters["id"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                        val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                        }
                        val pid = call.parameters["pid"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Pastovyklė ID required"))
                        val pidUUID = try { UUID.fromString(pid) } catch (e: Exception) {
                            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid pastovyklė ID"))
                        }

                        val canIssue = eventService.canManageEventInventory(eventUUID, tuntasUUID, userId) ||
                            eventService.isPastovykleResponsible(eventUUID, pidUUID, tuntasUUID, userId)
                        if (!canIssue) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))

                        val request = call.receiveValidated<AssignPastovykleInventoryRequest>()

                        eventService.assignInventory(eventUUID, pidUUID, tuntasUUID, userId, request)
                            .onSuccess { call.respond(HttpStatusCode.Created, it) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to assign inventory"))
                            }
                    }

                    put("{invId}") {
                        val principal = call.principal<JWTPrincipal>()!!
                        val userId = UUID.fromString(principal.getClaim("userId", String::class))

                        val tuntasId = call.request.headers["X-Tuntas-Id"]
                            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                        val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                        }

                        val eventId = call.parameters["id"]
                            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                        val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                        }
                        val pid = call.parameters["pid"]
                            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Pastovyklė ID required"))
                        val pidUUID = try { UUID.fromString(pid) } catch (e: Exception) {
                            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid pastovyklė ID"))
                        }

                        val canIssue = eventService.canManageEventInventory(eventUUID, tuntasUUID, userId) ||
                            eventService.isPastovykleResponsible(eventUUID, pidUUID, tuntasUUID, userId)
                        if (!canIssue) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))

                        val invId = call.parameters["invId"]
                            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Inventory ID required"))
                        val invUUID = try { UUID.fromString(invId) } catch (e: Exception) {
                            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid inventory ID"))
                        }

                        val request = call.receiveValidated<UpdatePastovykleInventoryRequest>()

                        eventService.updateInventoryAssignment(eventUUID, pidUUID, invUUID, tuntasUUID, request)
                            .onSuccess { call.respond(HttpStatusCode.OK, it) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to update inventory assignment"))
                            }
                    }

                    delete("{invId}") {
                        val tuntasId = call.request.headers["X-Tuntas-Id"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                        val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                            return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                        }

                        val eventId = call.parameters["id"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                        val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                            return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                        }
                        if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@delete

                        val pid = call.parameters["pid"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Pastovyklė ID required"))
                        val pidUUID = try { UUID.fromString(pid) } catch (e: Exception) {
                            return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid pastovyklė ID"))
                        }

                        val invId = call.parameters["invId"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Inventory ID required"))
                        val invUUID = try { UUID.fromString(invId) } catch (e: Exception) {
                            return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid inventory ID"))
                        }

                        eventService.removeInventoryAssignment(eventUUID, pidUUID, invUUID, tuntasUUID)
                            .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Inventory assignment removed")) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to remove inventory assignment"))
                            }
                    }
                }

                route("{pid}/requests") {
                    get {
                        val tuntasUUID = parseTuntasId() ?: return@get
                        val eventUUID = parseEventId() ?: return@get
                        val pastovykleUUID = parseUuidParameter("pid", "Invalid pastovyklė ID") ?: return@get
                        if (!canAccessPastovykle(eventService, tuntasUUID, eventUUID, pastovykleUUID)) return@get

                        eventService.getPastovykleRequests(eventUUID, pastovykleUUID, tuntasUUID)
                            .onSuccess { call.respond(HttpStatusCode.OK, it) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to fetch pastovykle requests"))
                            }
                    }

                    post {
                        val principal = call.principal<JWTPrincipal>()!!
                        val userId = UUID.fromString(principal.getClaim("userId", String::class))
                        val tuntasUUID = parseTuntasId() ?: return@post
                        val eventUUID = parseEventId() ?: return@post
                        val pastovykleUUID = parseUuidParameter("pid", "Invalid pastovyklė ID") ?: return@post
                        if (!canAccessPastovykle(eventService, tuntasUUID, eventUUID, pastovykleUUID)) return@post

                        val request = call.receiveValidated<CreatePastovykleInventoryRequestRequest>()
                        eventService.createPastovykleRequest(eventUUID, pastovykleUUID, tuntasUUID, userId, request)
                            .onSuccess {
                                notifyInventoryRequestAssignment(firebaseNotificationService, tuntasUUID, it, request.responsibleUserId != null)
                                call.respond(HttpStatusCode.Created, it)
                            }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to create pastovykle request"))
                            }
                    }

                    put("{requestId}") {
                        val userId = currentUserId() ?: return@put
                        val tuntasUUID = parseTuntasId() ?: return@put
                        val eventUUID = parseEventId() ?: return@put
                        val pastovykleUUID = parseUuidParameter("pid", "Invalid pastovykle ID") ?: return@put
                        val requestUUID = parseUuidParameter("requestId", "Invalid request ID") ?: return@put
                        if (!canAccessPastovykle(eventService, tuntasUUID, eventUUID, pastovykleUUID)) return@put
                        val request = call.receiveValidated<UpdateEventInventoryRequestRequest>()
                        eventService.updateInventoryRequest(eventUUID, requestUUID, tuntasUUID, userId, request)
                            .onSuccess {
                                notifyInventoryRequestAssignment(firebaseNotificationService, tuntasUUID, it, request.responsibleUserId != null)
                                call.respond(HttpStatusCode.OK, it)
                            }
                            .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to update request")) }
                    }

                    post("{requestId}/approve") {
                        val principal = call.principal<JWTPrincipal>()!!
                        val userId = UUID.fromString(principal.getClaim("userId", String::class))
                        val tuntasUUID = parseTuntasId() ?: return@post
                        val eventUUID = parseEventId() ?: return@post
                        val pastovykleUUID = parseUuidParameter("pid", "Invalid pastovyklė ID") ?: return@post
                        val requestUUID = parseUuidParameter("requestId", "Invalid request ID") ?: return@post
                        if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post

                        eventService.approvePastovykleRequest(eventUUID, pastovykleUUID, requestUUID, tuntasUUID, userId)
                            .onSuccess { call.respond(HttpStatusCode.OK, it) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to approve request"))
                            }
                    }

                    post("{requestId}/reject") {
                        val principal = call.principal<JWTPrincipal>()!!
                        val userId = UUID.fromString(principal.getClaim("userId", String::class))
                        val tuntasUUID = parseTuntasId() ?: return@post
                        val eventUUID = parseEventId() ?: return@post
                        val pastovykleUUID = parseUuidParameter("pid", "Invalid pastovyklė ID") ?: return@post
                        val requestUUID = parseUuidParameter("requestId", "Invalid request ID") ?: return@post
                        if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post

                        eventService.rejectPastovykleRequest(eventUUID, pastovykleUUID, requestUUID, tuntasUUID, userId)
                            .onSuccess { call.respond(HttpStatusCode.OK, it) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to reject request"))
                            }
                    }

                    post("{requestId}/self-provided") {
                        val principal = call.principal<JWTPrincipal>()!!
                        val userId = UUID.fromString(principal.getClaim("userId", String::class))
                        val tuntasUUID = parseTuntasId() ?: return@post
                        val eventUUID = parseEventId() ?: return@post
                        val pastovykleUUID = parseUuidParameter("pid", "Invalid pastovyklė ID") ?: return@post
                        val requestUUID = parseUuidParameter("requestId", "Invalid request ID") ?: return@post
                        if (!canAccessPastovykle(eventService, tuntasUUID, eventUUID, pastovykleUUID)) return@post

                        val request = call.receiveValidated<MarkPastovykleInventoryRequestSelfProvidedRequest>()
                        eventService.markPastovykleRequestSelfProvided(eventUUID, pastovykleUUID, requestUUID, tuntasUUID, userId, request)
                            .onSuccess { call.respond(HttpStatusCode.OK, it) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to mark request as self provided"))
                            }
                    }

                    post("{requestId}/fulfill") {
                        val principal = call.principal<JWTPrincipal>()!!
                        val userId = UUID.fromString(principal.getClaim("userId", String::class))
                        val tuntasUUID = parseTuntasId() ?: return@post
                        val eventUUID = parseEventId() ?: return@post
                        val pastovykleUUID = parseUuidParameter("pid", "Invalid pastovyklė ID") ?: return@post
                        val requestUUID = parseUuidParameter("requestId", "Invalid request ID") ?: return@post
                        if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post

                        val request = call.receiveValidated<FulfillPastovykleInventoryRequestRequest>()
                        eventService.fulfillPastovykleRequest(eventUUID, pastovykleUUID, requestUUID, tuntasUUID, userId, request)
                            .onSuccess { call.respond(HttpStatusCode.OK, it) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to fulfill request"))
                            }
                    }
                }

                post("{pid}/assign-from-unit") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    val pastovykleUUID = parseUuidParameter("pid", "Invalid pastovyklė ID") ?: return@post
                    if (!canAccessPastovykle(eventService, tuntasUUID, eventUUID, pastovykleUUID)) return@post

                    val request = call.receiveValidated<AssignUnitInventoryToPastovykleRequest>()
                    eventService.assignUnitInventoryToPastovykle(eventUUID, pastovykleUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e ->
                            val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                            call.respond(status, ErrorResponse(e.message ?: "Failed to assign unit inventory"))
                        }
                }
            }
        }
    }
}

private suspend fun RoutingContext.canManageEvent(
    eventService: EventService,
    tuntasId: UUID,
    eventId: UUID
): Boolean {
    val principal = call.principal<JWTPrincipal>()
        ?: return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated")).let { false }
    val userId = try {
        UUID.fromString(principal.getClaim("userId", String::class))
    } catch (e: Exception) {
        return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token")).let { false }
    }
    if (eventService.canManageEvent(eventId, tuntasId, userId)) return true
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
    return false
}

private suspend fun RoutingContext.canViewEvents(
    eventService: EventService,
    tuntasId: UUID
): Boolean {
    val principal = call.principal<JWTPrincipal>()
        ?: return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated")).let { false }
    val userId = try {
        UUID.fromString(principal.getClaim("userId", String::class))
    } catch (e: Exception) {
        return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token")).let { false }
    }
    if (eventService.canViewEvents(userId, tuntasId)) return true
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
    return false
}

private suspend fun RoutingContext.canViewEvent(
    eventService: EventService,
    tuntasId: UUID,
    eventId: UUID
): Boolean {
    val principal = call.principal<JWTPrincipal>()
        ?: return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated")).let { false }
    val userId = try {
        UUID.fromString(principal.getClaim("userId", String::class))
    } catch (e: Exception) {
        return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token")).let { false }
    }
    if (eventService.canViewEvent(userId, tuntasId, eventId)) return true
    if (eventService.hasResponsiblePastovykleForEvent(userId, tuntasId, eventId)) return true
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
    return false
}

private suspend fun RoutingContext.canStartEvent(
    eventService: EventService,
    tuntasId: UUID,
    eventId: UUID
): Boolean {
    val principal = call.principal<JWTPrincipal>()
        ?: return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated")).let { false }
    val userId = try {
        UUID.fromString(principal.getClaim("userId", String::class))
    } catch (e: Exception) {
        return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token")).let { false }
    }
    if (eventService.canStartEvent(eventId, tuntasId, userId)) return true
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
    return false
}

private suspend fun RoutingContext.canManageEventInventory(
    eventService: EventService,
    tuntasId: UUID,
    eventId: UUID
): Boolean {
    val principal = call.principal<JWTPrincipal>()
        ?: return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated")).let { false }
    val userId = try {
        UUID.fromString(principal.getClaim("userId", String::class))
    } catch (e: Exception) {
        return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token")).let { false }
    }
    if (eventService.canManageEventInventory(eventId, tuntasId, userId)) return true
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
    return false
}

private suspend fun RoutingContext.canManageEventFinance(
    eventService: EventService,
    tuntasId: UUID,
    eventId: UUID
): Boolean {
    val principal = call.principal<JWTPrincipal>()
        ?: return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated")).let { false }
    val userId = try {
        UUID.fromString(principal.getClaim("userId", String::class))
    } catch (e: Exception) {
        return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token")).let { false }
    }
    if (eventService.canManageEventFinance(eventId, tuntasId, userId)) return true
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
    return false
}

private suspend fun RoutingContext.canViewEventInventory(
    eventService: EventService,
    tuntasId: UUID,
    eventId: UUID
): Boolean {
    val principal = call.principal<JWTPrincipal>()
        ?: return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated")).let { false }
    val userId = try {
        UUID.fromString(principal.getClaim("userId", String::class))
    } catch (e: Exception) {
        return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token")).let { false }
    }
    if (eventService.canViewEventInventory(eventId, tuntasId, userId)) return true
    if (eventService.hasResponsiblePastovykleForEvent(userId, tuntasId, eventId)) return true
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
    return false
}

private suspend fun RoutingContext.canAccessPastovykle(
    eventService: EventService,
    tuntasId: UUID,
    eventId: UUID,
    pastovykleId: UUID
): Boolean {
    val principal = call.principal<JWTPrincipal>()
        ?: return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated")).let { false }
    val userId = try {
        UUID.fromString(principal.getClaim("userId", String::class))
    } catch (e: Exception) {
        return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token")).let { false }
    }
    if (eventService.canManageEvent(eventId, tuntasId, userId)) return true
    if (eventService.canManageEventInventory(eventId, tuntasId, userId)) return true
    if (eventService.isPastovykleResponsible(eventId, pastovykleId, tuntasId, userId)) return true
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
    return false
}

private suspend fun RoutingContext.canDownloadPurchaseInvoice(
    eventService: EventService,
    tuntasId: UUID,
    eventId: UUID
): Boolean {
    val principal = call.principal<JWTPrincipal>()
        ?: return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated")).let { false }
    val userId = try {
        UUID.fromString(principal.getClaim("userId", String::class))
    } catch (e: Exception) {
        return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token")).let { false }
    }
    if (eventService.canManageEventFinance(eventId, tuntasId, userId)) return true
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
    return false
}

private suspend fun RoutingContext.currentUserId(): UUID? {
    val principal = call.principal<JWTPrincipal>()
        ?: return call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not authenticated")).let { null }
    return try {
        UUID.fromString(principal.getClaim("userId", String::class))
    } catch (e: Exception) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
        null
    }
}

private suspend fun RoutingContext.parseTuntasId(): UUID? {
    val tuntasId = call.request.headers["X-Tuntas-Id"]
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required")).let { null }
    return try {
        UUID.fromString(tuntasId)
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
        null
    }
}

private suspend fun RoutingContext.parseEventId(): UUID? {
    val eventId = call.parameters["id"]
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required")).let { null }
    return try {
        UUID.fromString(eventId)
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
        null
    }
}

private suspend fun RoutingContext.parseUuidParameter(name: String, invalidMessage: String): UUID? {
    val value = call.parameters[name]
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("$name required")).let { null }
    return try {
        UUID.fromString(value)
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(invalidMessage))
        null
    }
}

private fun parseInstantOrNull(value: String): kotlinx.datetime.Instant? = try {
    kotlinx.datetime.Instant.parse(value)
} catch (_: Exception) {
    null
}
