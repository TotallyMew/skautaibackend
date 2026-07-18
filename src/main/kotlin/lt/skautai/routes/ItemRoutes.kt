package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.CreateItemRequest
import lt.skautai.models.requests.CreateStorageAuditSessionRequest
import lt.skautai.models.requests.ConsumeItemRequest
import lt.skautai.models.requests.DirectItemLoanRequest
import lt.skautai.models.requests.ReturnDirectItemLoanRequest
import lt.skautai.models.requests.ReturnItemToSharedRequest
import lt.skautai.models.requests.RestockItemRequest
import lt.skautai.models.requests.ReviewItemAdditionRequest
import lt.skautai.models.requests.TransferItemToUnitRequest
import lt.skautai.models.requests.UpsertStorageAuditChecksRequest
import lt.skautai.models.requests.UpdateItemRequest
import lt.skautai.models.requests.WriteOffItemRequest
import lt.skautai.models.responses.DuplicateItemConflictResponse
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.ItemQrResolveResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.plugins.checkPermission
import lt.skautai.plugins.resolveUserPermissions
import lt.skautai.services.DuplicateItemConflictException
import lt.skautai.services.ItemCheckService
import lt.skautai.services.ItemScopeHelper
import lt.skautai.services.ItemService
import lt.skautai.services.PermissionContextService
import lt.skautai.services.SeniorUnitPrivacyService
import java.util.*

fun Route.itemRoutes(itemService: ItemService, itemCheckService: ItemCheckService, apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        route("$apiPrefix/items") {

            get {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val custodianId = call.request.queryParameters["custodianId"]
                val type = call.request.queryParameters["type"]
                val category = call.request.queryParameters["category"]
                val status = call.request.queryParameters["status"]
                val sharedOnly = call.request.queryParameters["sharedOnly"]?.toBooleanStrictOrNull() ?: false
                val createdByUserId = call.request.queryParameters["createdByUserId"]
                val updatedAfter = call.request.queryParameters["updatedAfter"]?.let(::parseInstantOrNull)
                val searchQuery = call.request.queryParameters["q"]?.trim()?.take(100)
                val limit = call.request.queryParameters["limit"]?.let { raw ->
                    raw.toIntOrNull()
                        ?.takeIf { it in 1..200 }
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("limit must be between 1 and 200"))
                }
                val offset = call.request.queryParameters["offset"]?.let { raw ->
                    raw.toIntOrNull()
                        ?.takeIf { it >= 0 }
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("offset must be 0 or greater"))
                } ?: 0

                itemService.getItems(
                    tuntasId = tuntasUUID,
                    requestingUserId = userId,
                    custodianId = custodianId,
                    type = type,
                    category = category,
                    status = status,
                    sharedOnly = sharedOnly,
                    createdByUserId = createdByUserId,
                    updatedAfter = updatedAfter,
                    searchQuery = searchQuery,
                    limit = limit,
                    offset = offset
                )
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "Failed to fetch items")) }
            }

            get("direct-loans") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val activeOnly = call.request.queryParameters["activeOnly"]?.toBooleanStrictOrNull() ?: true
                itemService.getDirectItemLoansForTuntas(tuntasUUID, userId, activeOnly)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to fetch direct loans")) }
            }

            get("{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val itemId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                itemService.getItem(itemUUID, tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Item not found")) }
            }

            get("{id}/assignments") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val itemId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                itemService.getItemAssignments(itemUUID, tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Item not found")) }
            }

            get("{id}/direct-loans") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val itemId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                itemService.getDirectItemLoans(itemUUID, tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Item not found")) }
            }

            post("{id}/direct-loans") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val itemId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                val scopeInfo = ItemScopeHelper.getItemScopeInfo(itemUUID, tuntasUUID)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
                if (!canAccessSeniorOwnedInventory(userId, tuntasUUID, scopeInfo.custodianId, scopeInfo.origin)) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
                }
                if (!checkPermission("items.update", tuntasUUID, scopeInfo.custodianId)) return@post
                if (
                    scopeInfo.origin in listOf("TRANSFERRED_FROM_TUNTAS", "from_shared") &&
                    !PermissionContextService.resolve(userId, tuntasUUID).hasAll("items.update")
                ) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val request = call.receiveValidated<DirectItemLoanRequest>()
                itemService.issueDirectItemLoan(itemUUID, tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to issue item")) }
            }

            post("{id}/direct-loans/{loanId}/return") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val itemId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }
                val loanId = call.parameters["loanId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Loan ID required"))
                val loanUUID = try { UUID.fromString(loanId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid loan ID"))
                }

                val scopeInfo = ItemScopeHelper.getItemScopeInfo(itemUUID, tuntasUUID)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
                if (!canAccessSeniorOwnedInventory(userId, tuntasUUID, scopeInfo.custodianId, scopeInfo.origin)) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
                }
                if (!checkPermission("items.update", tuntasUUID, scopeInfo.custodianId)) return@post
                if (
                    scopeInfo.origin in listOf("TRANSFERRED_FROM_TUNTAS", "from_shared") &&
                    !PermissionContextService.resolve(userId, tuntasUUID).hasAll("items.update")
                ) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val request = call.receiveValidated<ReturnDirectItemLoanRequest>()
                itemService.returnDirectItemLoan(itemUUID, loanUUID, tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to return item")) }
            }

            get("{id}/condition-log") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val itemId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                itemService.getItemConditionLog(itemUUID, tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Item not found")) }
            }

            get("{id}/transfers") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val itemId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                itemService.getItemTransfers(itemUUID, tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Item not found")) }
            }

            get("{id}/history") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val itemId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                itemService.getItemHistory(itemUUID, tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Item not found")) }
            }

            get("resolve-qr/{token}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val token = call.parameters["token"]?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("QR token required"))

                itemService.resolveItemIdByQrToken(token, tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, ItemQrResolveResponse(itemId = it.toString())) }
                    .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Item not found")) }
            }

            route("audit-sessions") {
                get {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }
                    if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                        return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    }
                    val status = call.request.queryParameters["status"]
                    itemCheckService.listStorageAuditSessions(tuntasUUID, status)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to list audit sessions")) }
                }

                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }
                    if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    }
                    val request = call.receiveValidated<CreateStorageAuditSessionRequest>()
                    itemCheckService.createStorageAuditSession(tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.Created, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create audit session")) }
                }

                get("{sessionId}") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }
                    if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                        return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    }
                    val sessionId = call.parameters["sessionId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Session ID required"))
                    val sessionUUID = try { UUID.fromString(sessionId) } catch (e: Exception) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid session ID"))
                    }
                    itemCheckService.getStorageAuditSession(sessionUUID, tuntasUUID)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "Audit session not found")) }
                }

                post("{sessionId}/checks") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }
                    if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    }
                    val sessionId = call.parameters["sessionId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Session ID required"))
                    val sessionUUID = try { UUID.fromString(sessionId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid session ID"))
                    }
                    val request = call.receiveValidated<UpsertStorageAuditChecksRequest>()
                    itemCheckService.upsertStorageAuditChecks(sessionUUID, tuntasUUID, userId, request)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to save audit checks")) }
                }

                post("{sessionId}/complete") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = UUID.fromString(principal.getClaim("userId", String::class))
                    val tuntasId = call.request.headers["X-Tuntas-Id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                    val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                    }
                    if (!PermissionContextService.resolve(userId, tuntasUUID).has("items.view")) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    }
                    val sessionId = call.parameters["sessionId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Session ID required"))
                    val sessionUUID = try { UUID.fromString(sessionId) } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid session ID"))
                    }
                    itemCheckService.completeStorageAuditSession(sessionUUID, tuntasUUID, userId)
                        .onSuccess { call.respond(HttpStatusCode.OK, it) }
                        .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to complete audit session")) }
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

                val request = call.receiveValidated<CreateItemRequest>()

                val targetOrgUnitId = request.custodianId?.let {
                    try { UUID.fromString(it) } catch (e: Exception) { null }
                }
                if (
                    targetOrgUnitId != null &&
                    SeniorUnitPrivacyService.isSeniorUnit(targetOrgUnitId, tuntasUUID) &&
                    !SeniorUnitPrivacyService.userHasInternalAccess(userId, tuntasUUID, targetOrgUnitId)
                ) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val userPerms = resolveUserPermissions(userId, tuntasUUID)
                val isPendingApproval: Boolean = if (targetOrgUnitId != null) {
                    val canDirect = userPerms.any { it.permissionName == "items.create" && it.scope == "ALL" } ||
                        userPerms.any { it.permissionName == "items.create" && it.scope == "OWN_UNIT" && targetOrgUnitId in it.userOrgUnitIds }
                    val canSubmit = !canDirect && (
                        userPerms.any { it.permissionName == "items.create.submit" && it.scope == "ALL" } ||
                            userPerms.any { it.permissionName == "items.create.submit" && it.scope == "OWN_UNIT" && targetOrgUnitId in it.userOrgUnitIds }
                        )
                    if (!canDirect && !canSubmit) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    }
                    !canDirect
                } else {
                    val canDirect = userPerms.any { it.permissionName == "items.create" && it.scope == "ALL" }
                    val canSubmit = !canDirect && (
                        userPerms.any { it.permissionName == "items.create" && it.scope == "OWN_UNIT" } ||
                            userPerms.any { it.permissionName == "items.create.submit" }
                        )
                    if (!canDirect && !canSubmit) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    }
                    !canDirect
                }

                itemService.createItem(tuntasUUID, userId, request, isPendingApproval)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure {
                        when (it) {
                            is DuplicateItemConflictException -> call.respond(
                                HttpStatusCode.Conflict,
                                DuplicateItemConflictResponse(
                                    error = "Rastas toks pats daiktas. Pasirinkite: prideti prie esamo arba sukurti nauja irasa.",
                                    duplicateItem = it.duplicateItem
                                )
                            )
                            else -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to create item"))
                        }
                    }
            }

            put("{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

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

                val request = call.receiveValidated<UpdateItemRequest>()

                val newCustodianId = request.custodianId?.let {
                    try { UUID.fromString(it) } catch (e: Exception) {
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid custodian ID"))
                    }
                }

                val scopeInfo = ItemScopeHelper.getItemScopeInfo(itemUUID, tuntasUUID)
                val targetOrgUnitId = scopeInfo?.custodianId
                if (!canAccessSeniorOwnedInventory(userId, tuntasUUID, targetOrgUnitId, scopeInfo?.origin)) {
                    return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
                }
                if (!canAccessSeniorOwnedInventory(userId, tuntasUUID, newCustodianId, scopeInfo?.origin)) {
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }
                if (
                    scopeInfo?.origin in listOf("TRANSFERRED_FROM_TUNTAS", "from_shared") &&
                    !PermissionContextService.resolve(userId, tuntasUUID).hasAll("items.update")
                ) {
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Transferred tuntas inventory is read-only for unit leadership"))
                }

                if (!checkPermission("items.update", tuntasUUID, targetOrgUnitId)) return@put
                if (newCustodianId != null && newCustodianId != targetOrgUnitId) {
                    if (!checkPermission("items.update", tuntasUUID, newCustodianId)) return@put
                }

                itemService.updateItem(itemUUID, tuntasUUID, request, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to update item")) }
            }

            post("{id}/restock") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val itemId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                val scopeInfo = ItemScopeHelper.getItemScopeInfo(itemUUID, tuntasUUID)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
                val targetOrgUnitId = scopeInfo.custodianId
                if (!canAccessSeniorOwnedInventory(userId, tuntasUUID, targetOrgUnitId, scopeInfo.origin)) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
                }
                if (!checkPermission("items.update", tuntasUUID, targetOrgUnitId)) return@post
                if (
                    scopeInfo.origin in listOf("TRANSFERRED_FROM_TUNTAS", "from_shared") &&
                    !PermissionContextService.resolve(userId, tuntasUUID).hasAll("items.update")
                ) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val request = call.receiveValidated<RestockItemRequest>()
                itemService.restockItem(itemUUID, tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to restock item")) }
            }

            post("{id}/consume") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val itemId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                val scopeInfo = ItemScopeHelper.getItemScopeInfo(itemUUID, tuntasUUID)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
                if (!canAccessSeniorOwnedInventory(userId, tuntasUUID, scopeInfo.custodianId, scopeInfo.origin)) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
                }
                if (!checkPermission("items.update", tuntasUUID, scopeInfo.custodianId)) return@post
                if (
                    scopeInfo.origin in listOf("TRANSFERRED_FROM_TUNTAS", "from_shared") &&
                    !PermissionContextService.resolve(userId, tuntasUUID).hasAll("items.update")
                ) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Transferred tuntas inventory is read-only for unit leadership"))
                }

                val request = call.receiveValidated<ConsumeItemRequest>()
                itemService.consumeItem(itemUUID, tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to consume item")) }
            }

            post("{id}/review") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val itemId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                val reviewerPerms = resolveUserPermissions(userId, tuntasUUID)
                val canReview = reviewerPerms.any {
                    it.permissionName == "items.review"
                }
                if (!canReview) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val request = call.receiveValidated<ReviewItemAdditionRequest>()
                itemService.reviewItemAddition(itemUUID, tuntasUUID, userId, request, reviewerPerms)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to review item")) }
            }

            post("{id}/transfer-to-unit") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val permissions = PermissionContextService.resolve(userId, tuntasUUID)
                if (!permissions.hasAll("items.transfer")) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val itemId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                val request = call.receiveValidated<TransferItemToUnitRequest>()
                itemService.transferSharedItemToUnit(itemUUID, tuntasUUID, request, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to transfer item")) }
            }

            post("{id}/return-to-shared") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val itemId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                val scopeInfo = ItemScopeHelper.getItemScopeInfo(itemUUID, tuntasUUID)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
                val permissions = PermissionContextService.resolve(userId, tuntasUUID)
                if (scopeInfo.origin == "TRANSFERRED_FROM_TUNTAS" && !permissions.hasAll("items.transfer")) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Transferred tuntas inventory is read-only for unit leadership"))
                }
                if (!permissions.hasAll("items.transfer") && !permissions.targetAllowed("items.update", scopeInfo.custodianId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }

                val request = call.receiveValidated<ReturnItemToSharedRequest>()
                itemService.returnTransferredItemToShared(itemUUID, tuntasUUID, request, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to return item")) }
            }

            post("{id}/write-off") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

                val tuntasId = call.request.headers["X-Tuntas-Id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val tuntasUUID = try { UUID.fromString(tuntasId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tuntas ID"))
                }

                val itemId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Item ID required"))
                val itemUUID = try { UUID.fromString(itemId) } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid item ID"))
                }

                val scopeInfo = ItemScopeHelper.getItemScopeInfo(itemUUID, tuntasUUID)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
                val targetOrgUnitId = if (scopeInfo.origin == "TRANSFERRED_FROM_TUNTAS") null else scopeInfo.custodianId
                if (!canAccessSeniorOwnedInventory(userId, tuntasUUID, scopeInfo.custodianId, scopeInfo.origin)) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
                }
                if (!checkPermission("items.delete", tuntasUUID, targetOrgUnitId)) return@post

                val request = call.receiveValidated<WriteOffItemRequest>()
                itemService.writeOffItem(itemUUID, tuntasUUID, userId, request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to write off item")) }
            }

            delete("{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))

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
                if (
                    deleteScopeInfo != null &&
                    !canAccessSeniorOwnedInventory(
                        userId,
                        tuntasUUID,
                        deleteScopeInfo.custodianId,
                        deleteScopeInfo.origin
                    )
                ) {
                    return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
                }
                val deleteTargetOrgUnitId = if (deleteScopeInfo?.origin == "TRANSFERRED_FROM_TUNTAS") null else deleteScopeInfo?.custodianId
                if (!checkPermission("items.delete", tuntasUUID, deleteTargetOrgUnitId)) return@delete

                itemService.deleteItem(itemUUID, tuntasUUID, userId)
                    .onSuccess { call.respond(HttpStatusCode.OK, MessageResponse("Item deactivated")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Failed to delete item")) }
            }
        }
    }
}

private fun parseInstantOrNull(value: String): kotlinx.datetime.Instant? = try {
    kotlinx.datetime.Instant.parse(value)
} catch (_: Exception) {
    null
}

private fun canAccessSeniorOwnedInventory(
    userId: UUID,
    tuntasId: UUID,
    custodianId: UUID?,
    origin: String?
): Boolean {
    if (custodianId == null || origin == "TRANSFERRED_FROM_TUNTAS") return true
    if (!SeniorUnitPrivacyService.isSeniorUnit(custodianId, tuntasId)) return true
    return SeniorUnitPrivacyService.userHasInternalAccess(userId, tuntasId, custodianId)
}

