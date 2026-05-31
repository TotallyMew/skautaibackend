package lt.skautai.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import lt.skautai.database.tables.BendrasInventoryRequests
import lt.skautai.database.tables.DraugoveRequisitions
import lt.skautai.database.tables.Events
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.Locations
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.Reservations
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.database.tables.Users
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MobileCacheStateResourceResponse
import lt.skautai.models.responses.MobileCacheStateResponse
import lt.skautai.models.responses.MobileHomeSummaryResponse
import lt.skautai.models.responses.OrganizationalUnitResponse
import lt.skautai.services.BendrasInventoryRequestService
import lt.skautai.services.EventService
import lt.skautai.services.ItemService
import lt.skautai.services.MyTaskService
import lt.skautai.services.OrganizationalUnitService
import lt.skautai.services.PermissionContextService
import lt.skautai.services.RequisitionService
import lt.skautai.services.ReservationService
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

fun Route.mobileRoutes(
    itemService: ItemService,
    reservationService: ReservationService,
    bendrasInventoryRequestService: BendrasInventoryRequestService,
    requisitionService: RequisitionService,
    eventService: EventService,
    organizationalUnitService: OrganizationalUnitService,
    myTaskService: MyTaskService
) {
    authenticate("auth-jwt") {
        route("/api/mobile") {
            get("/home-summary") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.request.headers["X-Tuntas-Id"]?.let(::parseUuidOrNull)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))
                val activeUnitId = call.request.headers["X-Org-Unit-Id"]?.let(::parseUuidOrNull)

                val permissions = PermissionContextService.resolve(userId, tuntasId)
                val permissionNames = permissions.permissions.map { permission ->
                    if (permission.scope == "ALL") "${permission.permissionName}:ALL" else "${permission.permissionName}:OWN_UNIT"
                }.toSet()
                val visibleUnitIds = if (permissions.hasAll("units.view")) null else permissions.allUserOrgUnitIds
                val units = organizationalUnitService.getUnits(tuntasId, visibleUnitIds = visibleUnitIds)
                    .getOrNull()
                    ?.units
                    .orEmpty()
                val resolvedUnit = resolveActiveUnit(activeUnitId, units)

                val items = itemService.getItems(tuntasId, userId, status = "ACTIVE")
                    .getOrNull()
                    ?.items
                    .orEmpty()
                val pendingItems = if (permissions.has("items.review") || permissions.has("items.create")) {
                    itemService.getItems(tuntasId, userId, status = "PENDING_APPROVAL").getOrNull()?.items.orEmpty()
                } else emptyList()
                val canViewAllReservations = permissions.hasAll("reservations.approve")
                val approvableUnitIds = permissions.scopedUnitIds("reservations.approve").toList()
                val reservations = reservationService.getReservations(
                    tuntasId,
                    userId,
                    canViewAllReservations,
                    approvableUnitIds
                ).getOrNull()?.reservations.orEmpty()
                val isSharedRequestAdmin = permissions.hasAll("items.request.approve.bendras")
                val sharedRequestUnitIds = if (isSharedRequestAdmin) emptyList() else permissions.allUserOrgUnitIds.toList()
                val sharedRequests = bendrasInventoryRequestService.getAllRequests(
                    tuntasId,
                    userId,
                    isSharedRequestAdmin,
                    sharedRequestUnitIds
                ).getOrNull()?.requests.orEmpty()
                val isTopLevelRequisitionReviewer = permissions.hasAll("requisitions.approve")
                val requisitionUnitIds = if (isTopLevelRequisitionReviewer) emptyList() else permissions.allUserOrgUnitIds.toList()
                val requisitions = requisitionService.getAllRequests(
                    tuntasId,
                    userId,
                    isTopLevelRequisitionReviewer,
                    requisitionUnitIds
                ).getOrNull()?.requests.orEmpty()
                val tasks = myTaskService.getMyTasks(tuntasId, userId).getOrNull()

                call.respond(
                    HttpStatusCode.OK,
                    MobileHomeSummaryResponse(
                        activeUnitId = resolvedUnit?.id,
                        activeUnitName = resolvedUnit?.name,
                        availableUnits = units,
                        activeUnitItemCount = items.count { it.custodianId == resolvedUnit?.id && it.type != "INDIVIDUAL" },
                        activeUnitFromSharedCount = items.count { it.custodianId == resolvedUnit?.id && it.origin == "TRANSFERRED_FROM_TUNTAS" },
                        sharedInventoryCount = items.count { it.custodianId == null && it.type != "INDIVIDUAL" },
                        sharedPendingApprovalCount = pendingItems.count { it.custodianId == null && it.type != "INDIVIDUAL" },
                        personalLendingCount = items.count { it.type == "INDIVIDUAL" && it.createdByUserId == userId.toString() },
                        requisitionCount = requisitions.size,
                        myRequisitionCount = requisitions.count { it.createdByUserId == userId.toString() },
                        assignedRequisitionCount = requisitions.count { it.createdByUserId != userId.toString() && it.topLevelReviewStatus == "PENDING" && canReviewTopLevelRequisitions(permissionNames) },
                        sharedRequestCount = sharedRequests.count { it.topLevelStatus == "PENDING" },
                        myReservationCount = reservations.count { it.reservedByUserId == userId.toString() && it.status in activeReservationStatuses },
                        assignedReservationCount = reservations.count { it.status == "PENDING" && canApproveAnyReservation(permissionNames) },
                        trackedReservationCount = reservations.count { it.status in activeReservationStatuses && canApproveAnyReservation(permissionNames) },
                        activeReservations = reservations.filter { it.status in activeReservationStatuses }.take(5),
                        tasks = tasks?.tasks?.take(3).orEmpty(),
                        taskTotalCount = tasks?.total ?: 0
                    )
                )
            }

            get("/cache-state") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val tuntasId = call.request.headers["X-Tuntas-Id"]?.let(::parseUuidOrNull)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("X-Tuntas-Id header required"))

                PermissionContextService.resolve(userId, tuntasId)
                call.respond(
                    HttpStatusCode.OK,
                    MobileCacheStateResponse(buildCacheState(tuntasId))
                )
            }
        }
    }
}

private val activeReservationStatuses = setOf("APPROVED", "ACTIVE")

private fun parseUuidOrNull(value: String): UUID? = try {
    UUID.fromString(value)
} catch (_: Exception) {
    null
}

private fun resolveActiveUnit(activeUnitId: UUID?, units: List<OrganizationalUnitResponse>): OrganizationalUnitResponse? =
    activeUnitId?.toString()?.let { id -> units.firstOrNull { it.id == id } } ?: units.firstOrNull()

private fun canReviewTopLevelRequisitions(permissions: Set<String>): Boolean =
    "requisitions.approve:ALL" in permissions || "items.request.approve:ALL" in permissions

private fun canApproveAnyReservation(permissions: Set<String>): Boolean =
    "reservations.approve:ALL" in permissions || "reservations.approve:OWN_UNIT" in permissions

private fun buildCacheState(tuntasId: UUID): List<MobileCacheStateResourceResponse> = transaction {
    val itemRows = Items.selectAll().where { Items.tuntasId eq tuntasId }.toList()
    val reservationRows = Reservations.selectAll().where { Reservations.tuntasId eq tuntasId }.toList()
    val requestRows = BendrasInventoryRequests.selectAll().where { BendrasInventoryRequests.tuntasId eq tuntasId }.toList()
    val requisitionRows = DraugoveRequisitions.selectAll().where { DraugoveRequisitions.tuntasId eq tuntasId }.toList()
    val eventRows = Events.selectAll().where { Events.tuntasId eq tuntasId }.toList()
    val locationRows = Locations.selectAll().where { Locations.tuntasId eq tuntasId }.toList()
    val unitRows = OrganizationalUnits.selectAll().where { OrganizationalUnits.tuntasId eq tuntasId }.toList()
    val memberRows = Users
        .innerJoin(UserTuntasMemberships, { id }, { userId })
        .selectAll()
        .where {
            (UserTuntasMemberships.tuntasId eq tuntasId) and
                UserTuntasMemberships.leftAt.isNull()
        }
        .toList()

    listOf(
        resourceState("items", itemRows) { it[Items.updatedAt].toString() },
        resourceState("reservations", reservationRows) { it[Reservations.updatedAt].toString() },
        resourceState("requests", requestRows) { it[BendrasInventoryRequests.updatedAt].toString() },
        resourceState("requisitions", requisitionRows) { it[DraugoveRequisitions.updatedAt].toString() },
        resourceState("events", eventRows) { it[Events.updatedAt].toString() },
        resourceState("locations", locationRows) { it[Locations.updatedAt].toString() },
        resourceState("organizational_units", unitRows) { it[OrganizationalUnits.updatedAt].toString() },
        resourceState("members", memberRows) { row ->
            listOf(row[Users.updatedAt].toString(), row[UserTuntasMemberships.joinedAt].toString()).maxOrNull().orEmpty()
        }
    )
}

private fun resourceState(
    resource: String,
    rows: List<ResultRow>,
    updatedAt: (ResultRow) -> String
): MobileCacheStateResourceResponse {
    val maxUpdatedAt = rows.map(updatedAt).maxOrNull()
    return MobileCacheStateResourceResponse(
        resource = resource,
        maxUpdatedAt = maxUpdatedAt,
        total = rows.size,
        versionKey = "$resource:${maxUpdatedAt ?: "empty"}:${rows.size}"
    )
}
