package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.*
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.plugins.resolveUserPermissions
import lt.skautai.services.EventService
import java.io.File
import java.util.*

fun Route.eventRoutes(eventService: EventService) {
    authenticate("auth-jwt") {
        route("/api/events") {

            get {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!canViewEvents(eventService, tuntasUUID)) return@get

                val type = call.request.queryParameters["type"]
                val status = call.request.queryParameters["status"]

                eventService.getEvents(tuntasUUID, type, status)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "Failed to fetch events")) }
            }

            get("{id}") {
                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!canViewEvents(eventService, tuntasUUID)) return@get

                val eventId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                }

                eventService.getEvent(eventUUID, tuntasUUID)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Event not found")) }
            }

            post {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!eventService.isTuntasMember(userId, tuntasUUID)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member of this tuntas"))
                }

                val request = call.receive<CreateEventRequest>()

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
                val request = call.receive<UpdateEventRequest>()
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
                    .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Event cancelled")) }
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

                    val request = call.receive<AssignEventRoleRequest>()

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
                        .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Event role removed")) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to remove event role")) }
                }
            }

            put("{id}/stovykla-details") {
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

                val request = call.receive<UpdateStovyklaDetailsRequest>()

                eventService.updateStovyklaDetails(eventUUID, tuntasUUID, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { e ->
                        val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                        call.respond(status, ErrorResponse(e.message ?: "Failed to update stovykla details"))
                    }
            }

            route("{id}/inventory-plan") {
                get {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }
                    if (!canViewEvents(eventService, tuntasUUID)) return@get

                    val eventUUID = parseEventId() ?: return@get
                    eventService.getEventInventoryPlan(eventUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to fetch inventory plan")) }
                }
            }

            route("{id}/inventory-buckets") {
                post {
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val request = call.receive<CreateEventInventoryBucketRequest>()
                    eventService.createInventoryBucket(eventUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to create inventory bucket")) }
                }

                put("{bucketId}") {
                    val tuntasUUID = parseTuntasId() ?: return@put
                    val eventUUID = parseEventId() ?: return@put
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@put
                    val bucketUUID = parseUuidParameter("bucketId", "Invalid bucket ID") ?: return@put
                    val request = call.receive<UpdateEventInventoryBucketRequest>()
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
                        .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Inventory bucket deleted")) }
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
                    val request = call.receive<CreateEventInventoryItemRequest>()
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
                    val request = call.receive<CreateEventInventoryItemsBulkRequest>()
                    eventService.createInventoryItemsBulk(eventUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to create inventory items")) }
                }

                put("{inventoryItemId}") {
                    val tuntasUUID = parseTuntasId() ?: return@put
                    val eventUUID = parseEventId() ?: return@put
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@put
                    val inventoryItemUUID = parseUuidParameter("inventoryItemId", "Invalid inventory item ID") ?: return@put
                    val request = call.receive<UpdateEventInventoryItemRequest>()
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
                        .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Inventory item deleted")) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to delete inventory item")) }
                }
            }

            route("{id}/inventory-allocations") {
                post {
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val request = call.receive<CreateEventInventoryAllocationRequest>()
                    eventService.createInventoryAllocation(eventUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to create inventory allocation")) }
                }

                put("{allocationId}") {
                    val tuntasUUID = parseTuntasId() ?: return@put
                    val eventUUID = parseEventId() ?: return@put
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@put
                    val allocationUUID = parseUuidParameter("allocationId", "Invalid allocation ID") ?: return@put
                    val request = call.receive<UpdateEventInventoryAllocationRequest>()
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
                        .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Inventory allocation deleted")) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to delete inventory allocation")) }
                }
            }

            route("{id}/purchases") {
                get {
                    val tuntasUUID = parseTuntasId() ?: return@get
                    val eventUUID = parseEventId() ?: return@get
                    if (!canViewEvents(eventService, tuntasUUID)) return@get
                    eventService.getPurchases(eventUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to fetch purchases")) }
                }

                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val request = call.receive<CreateEventPurchaseRequest>()
                    eventService.createPurchase(eventUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to create purchase")) }
                }

                put("{purchaseId}") {
                    val tuntasUUID = parseTuntasId() ?: return@put
                    val eventUUID = parseEventId() ?: return@put
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@put
                    val purchaseUUID = parseUuidParameter("purchaseId", "Invalid purchase ID") ?: return@put
                    val request = call.receive<UpdateEventPurchaseRequest>()
                    eventService.updatePurchase(eventUUID, purchaseUUID, tuntasUUID, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to update purchase")) }
                }

                post("{purchaseId}/invoice") {
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val purchaseUUID = parseUuidParameter("purchaseId", "Invalid purchase ID") ?: return@post
                    val request = call.receive<AttachEventPurchaseInvoiceRequest>()
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
                            val file = File("uploads/documents", fileName)
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
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val purchaseUUID = parseUuidParameter("purchaseId", "Invalid purchase ID") ?: return@post
                    eventService.completePurchase(eventUUID, purchaseUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to complete purchase")) }
                }

                post("{purchaseId}/add-to-inventory") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasUUID = parseTuntasId() ?: return@post
                    val eventUUID = parseEventId() ?: return@post
                    if (!canManageEventInventory(eventService, tuntasUUID, eventUUID)) return@post
                    val purchaseUUID = parseUuidParameter("purchaseId", "Invalid purchase ID") ?: return@post
                    eventService.addPurchaseToInventory(eventUUID, purchaseUUID, tuntasUUID, userId)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to add purchase to inventory")) }
                }
            }

            route("{id}/pastovykles") {

                get {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    if (!canViewEvents(eventService, tuntasUUID)) return@get

                    val eventId = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                    val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                    }

                    eventService.getPastovykles(eventUUID, tuntasUUID)
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

                    val request = call.receive<CreatePastovykleRequest>()

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

                    if (!canViewEvents(eventService, tuntasUUID)) return@get

                    val eventId = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                    val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                    }

                    val pid = call.parameters["pid"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Pastovyklė ID required"))
                    val pidUUID = try { UUID.fromString(pid) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid pastovyklė ID"))
                    }

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

                    val request = call.receive<UpdatePastovykleRequest>()

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
                        .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Pastovyklė deleted")) }
                        .onFailure { e ->
                            val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                            call.respond(status, ErrorResponse(e.message ?: "Failed to delete pastovyklė"))
                        }
                }

                route("{pid}/inventory") {

                    get {
                        val tuntasId = call.request.headers["X-Tuntas-Id"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                        val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                            return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                        }

                        if (!canViewEvents(eventService, tuntasUUID)) return@get

                        val eventId = call.parameters["id"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                        val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                            return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                        }

                        val pid = call.parameters["pid"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Pastovyklė ID required"))
                        val pidUUID = try { UUID.fromString(pid) } catch (e: Exception) {
                            return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid pastovyklė ID"))
                        }

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

                        if (!checkPermission("events.inventory.distribute", tuntasUUID)) return@post

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

                        val request = call.receive<AssignPastovykleInventoryRequest>()

                        eventService.assignInventory(eventUUID, pidUUID, tuntasUUID, userId, request)
                            .onSuccess { call.respond(HttpStatusCode.Created, it) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to assign inventory"))
                            }
                    }

                    put("{invId}") {
                        val tuntasId = call.request.headers["X-Tuntas-Id"]
                            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                        val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                        }

                        if (!checkPermission("events.inventory.return", tuntasUUID)) return@put

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

                        val invId = call.parameters["invId"]
                            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Inventory ID required"))
                        val invUUID = try { UUID.fromString(invId) } catch (e: Exception) {
                            return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid inventory ID"))
                        }

                        val request = call.receive<UpdatePastovykleInventoryRequest>()

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

                        if (!checkPermission("events.inventory.distribute", tuntasUUID)) return@delete

                        val eventId = call.parameters["id"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                        val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                            return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                        }

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
                            .onSuccess { call.respond(HttpStatusCode.OK, ErrorResponse("Inventory assignment removed")) }
                            .onFailure { e ->
                                val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                                call.respond(status, ErrorResponse(e.message ?: "Failed to remove inventory assignment"))
                            }
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
    if (resolveUserPermissions(userId, tuntasId).any { it.permissionName == "events.manage" && it.scope == "ALL" }) return true
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
    if (resolveUserPermissions(userId, tuntasId).any { it.permissionName == "events.manage" && it.scope == "ALL" }) return true
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
    if (resolveUserPermissions(userId, tuntasId).any { it.permissionName == "events.inventory.distribute" && it.scope == "ALL" }) return true
    if (eventService.canManageEventInventory(eventId, tuntasId, userId)) return true
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
    val permissions = resolveUserPermissions(userId, tuntasId)
    if (permissions.any { it.permissionName == "event_purchases.invoice.download" && it.scope == "ALL" }) return true
    if (permissions.any { it.permissionName == "events.manage" && it.scope == "ALL" }) return true
    if (permissions.any { it.permissionName == "events.inventory.distribute" && it.scope == "ALL" }) return true
    if (eventService.canManageEventInventory(eventId, tuntasId, userId)) return true
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
    return false
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
