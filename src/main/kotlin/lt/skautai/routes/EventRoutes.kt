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
import lt.skautai.services.EventService
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

                if (!checkPermission("events.view", tuntasUUID)) return@get

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

                if (!checkPermission("events.view", tuntasUUID)) return@get

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

                if (!checkPermission("events.create", tuntasUUID)) return@post

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

                if (!checkPermission("events.manage", tuntasUUID)) return@put

                val eventId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                }

                val request = call.receive<UpdateEventRequest>()

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

                if (!checkPermission("events.manage", tuntasUUID)) return@delete

                val eventId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                }

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

                    if (!checkPermission("events.manage", tuntasUUID)) return@post

                    val eventId = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                    val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                    }

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

                    if (!checkPermission("events.manage", tuntasUUID)) return@delete

                    val eventId = call.parameters["id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                    val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                        return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                    }

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

                if (!checkPermission("events.manage", tuntasUUID)) return@put

                val eventId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                }

                val request = call.receive<UpdateStovyklaDetailsRequest>()

                eventService.updateStovyklaDetails(eventUUID, tuntasUUID, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { e ->
                        val status = if ("not found" in (e.message ?: "").lowercase()) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                        call.respond(status, ErrorResponse(e.message ?: "Failed to update stovykla details"))
                    }
            }

            route("{id}/pastovykles") {

                get {
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }

                    if (!checkPermission("events.view", tuntasUUID)) return@get

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

                    if (!checkPermission("events.manage", tuntasUUID)) return@post

                    val eventId = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Event ID required"))
                    val eventUUID = try { UUID.fromString(eventId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event ID"))
                    }

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

                    if (!checkPermission("events.view", tuntasUUID)) return@get

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

                    if (!checkPermission("events.manage", tuntasUUID)) return@put

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

                    if (!checkPermission("events.manage", tuntasUUID)) return@delete

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

                        if (!checkPermission("events.view", tuntasUUID)) return@get

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