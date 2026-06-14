package lt.skautai.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
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
import lt.skautai.services.PermissionContext
import lt.skautai.services.PermissionContextService
import lt.skautai.services.RequisitionService
import lt.skautai.services.ReservationService
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
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
                val canViewAllReservations = permissions.hasAll("reservations.approve")
                val approvableUnitIds = permissions.scopedUnitIds("reservations.approve").toList()
                val isSharedRequestAdmin = permissions.hasAll("items.request.approve.bendras")
                val sharedRequestUnitIds = if (isSharedRequestAdmin) emptyList() else permissions.allUserOrgUnitIds.toList()
                val isTopLevelRequisitionReviewer = permissions.hasAll("requisitions.approve")
                val requisitionUnitIds = if (isTopLevelRequisitionReviewer) emptyList() else permissions.allUserOrgUnitIds.toList()
                val counts = transaction {
                    val itemVisibility = itemVisibilitySql(permissions)
                    val reservationVisibility = reservationVisibilitySql(
                        userId = userId,
                        canViewAll = canViewAllReservations,
                        approvableUnitIds = approvableUnitIds
                    )
                    val sharedRequestVisibility = sharedRequestVisibilitySql(
                        isAdmin = isSharedRequestAdmin,
                        unitIds = sharedRequestUnitIds
                    )
                    val requisitionVisibility = requisitionVisibilitySql(
                        userId = userId,
                        isTopLevelReviewer = isTopLevelRequisitionReviewer,
                        unitIds = requisitionUnitIds
                    )
                    val canReviewReservations = canApproveAnyReservation(permissionNames)
                    val canReviewRequisitions = canReviewTopLevelRequisitions(permissionNames)

                    val itemCounts = itemHomeCountsSql(
                        tuntasId = tuntasId,
                        userId = userId,
                        activeUnitId = resolvedUnit?.id,
                        itemVisibility = itemVisibility,
                        includeSharedPendingApproval = permissions.has("items.review") || permissions.has("items.create")
                    )
                    val requisitionCounts = requisitionHomeCountsSql(
                        tuntasId = tuntasId,
                        userId = userId,
                        requisitionVisibility = requisitionVisibility,
                        includeAssigned = canReviewRequisitions
                    )
                    val reservationCounts = reservationHomeCountsSql(
                        tuntasId = tuntasId,
                        userId = userId,
                        reservationVisibility = reservationVisibility,
                        includeReviewCounts = canReviewReservations
                    )

                    HomeSummaryCounts(
                        activeUnitItemCount = itemCounts.activeUnitItemCount,
                        activeUnitFromSharedCount = itemCounts.activeUnitFromSharedCount,
                        sharedInventoryCount = itemCounts.sharedInventoryCount,
                        sharedPendingApprovalCount = itemCounts.sharedPendingApprovalCount,
                        personalLendingCount = itemCounts.personalLendingCount,
                        requisitionCount = requisitionCounts.requisitionCount,
                        myRequisitionCount = requisitionCounts.myRequisitionCount,
                        assignedRequisitionCount = requisitionCounts.assignedRequisitionCount,
                        sharedRequestCount = countSql(
                            """
                            SELECT COUNT(*)
                            FROM bendras_inventory_requests
                            WHERE tuntas_id = '${tuntasId}'
                              AND top_level_status = 'PENDING'
                              $sharedRequestVisibility
                            """.trimIndent()
                        ),
                        myReservationCount = reservationCounts.myReservationCount,
                        assignedReservationCount = reservationCounts.assignedReservationCount,
                        trackedReservationCount = reservationCounts.trackedReservationCount
                    )
                }
                val tasks = myTaskService.getMyTasks(tuntasId, userId).getOrNull()

                call.respond(
                    HttpStatusCode.OK,
                    MobileHomeSummaryResponse(
                        activeUnitId = resolvedUnit?.id,
                        activeUnitName = resolvedUnit?.name,
                        availableUnits = units,
                        activeUnitItemCount = counts.activeUnitItemCount,
                        activeUnitFromSharedCount = counts.activeUnitFromSharedCount,
                        sharedInventoryCount = counts.sharedInventoryCount,
                        sharedPendingApprovalCount = counts.sharedPendingApprovalCount,
                        personalLendingCount = counts.personalLendingCount,
                        requisitionCount = counts.requisitionCount,
                        myRequisitionCount = counts.myRequisitionCount,
                        assignedRequisitionCount = counts.assignedRequisitionCount,
                        sharedRequestCount = counts.sharedRequestCount,
                        myReservationCount = counts.myReservationCount,
                        assignedReservationCount = counts.assignedReservationCount,
                        trackedReservationCount = counts.trackedReservationCount,
                        activeReservations = emptyList(),
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

private data class HomeSummaryCounts(
    val activeUnitItemCount: Int,
    val activeUnitFromSharedCount: Int,
    val sharedInventoryCount: Int,
    val sharedPendingApprovalCount: Int,
    val personalLendingCount: Int,
    val requisitionCount: Int,
    val myRequisitionCount: Int,
    val assignedRequisitionCount: Int,
    val sharedRequestCount: Int,
    val myReservationCount: Int,
    val assignedReservationCount: Int,
    val trackedReservationCount: Int
)

private data class ItemHomeCounts(
    val activeUnitItemCount: Int = 0,
    val activeUnitFromSharedCount: Int = 0,
    val sharedInventoryCount: Int = 0,
    val sharedPendingApprovalCount: Int = 0,
    val personalLendingCount: Int = 0
)

private data class RequisitionHomeCounts(
    val requisitionCount: Int = 0,
    val myRequisitionCount: Int = 0,
    val assignedRequisitionCount: Int = 0
)

private data class ReservationHomeCounts(
    val myReservationCount: Int = 0,
    val assignedReservationCount: Int = 0,
    val trackedReservationCount: Int = 0
)

private fun org.jetbrains.exposed.sql.Transaction.itemHomeCountsSql(
    tuntasId: UUID,
    userId: UUID,
    activeUnitId: String?,
    itemVisibility: String,
    includeSharedPendingApproval: Boolean
): ItemHomeCounts {
    var counts = ItemHomeCounts()
    val activeUnitPredicate = activeUnitId?.let { "custodian_id = '$it'" } ?: "FALSE"
    val sharedPendingPredicate = if (includeSharedPendingApproval) {
        "status = 'PENDING_APPROVAL' AND custodian_id IS NULL AND type <> 'INDIVIDUAL'"
    } else {
        "FALSE"
    }
    exec(
        """
        SELECT
          COALESCE(SUM(CASE WHEN status = 'ACTIVE' AND $activeUnitPredicate AND type <> 'INDIVIDUAL' THEN 1 ELSE 0 END), 0)::int,
          COALESCE(SUM(CASE WHEN status = 'ACTIVE' AND $activeUnitPredicate AND origin = 'TRANSFERRED_FROM_TUNTAS' THEN 1 ELSE 0 END), 0)::int,
          COALESCE(SUM(CASE WHEN status = 'ACTIVE' AND custodian_id IS NULL AND type <> 'INDIVIDUAL' THEN 1 ELSE 0 END), 0)::int,
          COALESCE(SUM(CASE WHEN $sharedPendingPredicate THEN 1 ELSE 0 END), 0)::int,
          COALESCE(SUM(CASE WHEN status = 'ACTIVE' AND type = 'INDIVIDUAL' AND created_by_user_id = '$userId' THEN 1 ELSE 0 END), 0)::int
        FROM items
        WHERE tuntas_id = '$tuntasId'
          $itemVisibility
        """.trimIndent()
    ) { rs ->
        if (rs.next()) {
            counts = ItemHomeCounts(
                activeUnitItemCount = rs.getInt(1),
                activeUnitFromSharedCount = rs.getInt(2),
                sharedInventoryCount = rs.getInt(3),
                sharedPendingApprovalCount = rs.getInt(4),
                personalLendingCount = rs.getInt(5)
            )
        }
    }
    return counts
}

private fun org.jetbrains.exposed.sql.Transaction.requisitionHomeCountsSql(
    tuntasId: UUID,
    userId: UUID,
    requisitionVisibility: String,
    includeAssigned: Boolean
): RequisitionHomeCounts {
    var counts = RequisitionHomeCounts()
    val assignedPredicate = if (includeAssigned) {
        "created_by_user_id <> '$userId' AND top_level_review_status = 'PENDING'"
    } else {
        "FALSE"
    }
    exec(
        """
        SELECT
          COUNT(*)::int,
          COALESCE(SUM(CASE WHEN created_by_user_id = '$userId' THEN 1 ELSE 0 END), 0)::int,
          COALESCE(SUM(CASE WHEN $assignedPredicate THEN 1 ELSE 0 END), 0)::int
        FROM draugove_requisitions
        WHERE tuntas_id = '$tuntasId'
          $requisitionVisibility
        """.trimIndent()
    ) { rs ->
        if (rs.next()) {
            counts = RequisitionHomeCounts(
                requisitionCount = rs.getInt(1),
                myRequisitionCount = rs.getInt(2),
                assignedRequisitionCount = rs.getInt(3)
            )
        }
    }
    return counts
}

private fun org.jetbrains.exposed.sql.Transaction.reservationHomeCountsSql(
    tuntasId: UUID,
    userId: UUID,
    reservationVisibility: String,
    includeReviewCounts: Boolean
): ReservationHomeCounts {
    var counts = ReservationHomeCounts()
    val assignedPredicate = if (includeReviewCounts) "status = 'PENDING'" else "FALSE"
    val trackedPredicate = if (includeReviewCounts) "status IN ('APPROVED', 'ACTIVE')" else "FALSE"
    exec(
        """
        SELECT
          COUNT(DISTINCT CASE WHEN reserved_by_user_id = '$userId' AND status IN ('APPROVED', 'ACTIVE') THEN group_id END)::int,
          COUNT(DISTINCT CASE WHEN $assignedPredicate THEN group_id END)::int,
          COUNT(DISTINCT CASE WHEN $trackedPredicate THEN group_id END)::int
        FROM reservations
        WHERE tuntas_id = '$tuntasId'
          $reservationVisibility
        """.trimIndent()
    ) { rs ->
        if (rs.next()) {
            counts = ReservationHomeCounts(
                myReservationCount = rs.getInt(1),
                assignedReservationCount = rs.getInt(2),
                trackedReservationCount = rs.getInt(3)
            )
        }
    }
    return counts
}

private fun org.jetbrains.exposed.sql.Transaction.countSql(sql: String): Int {
    var value = 0
    exec(sql) { rs ->
        if (rs.next()) value = rs.getInt(1)
    }
    return value
}

private fun itemVisibilitySql(permissions: PermissionContext): String {
    if (permissions.hasAll("items.view") || permissions.hasAll("items.create") || permissions.hasAll("items.update")) {
        return ""
    }
    val unitIds = permissions.allUserOrgUnitIds
    if (unitIds.isEmpty()) return "AND custodian_id IS NULL"
    return "AND (custodian_id IS NULL OR custodian_id IN (${unitIds.sqlUuidList()}))"
}

private fun reservationVisibilitySql(
    userId: UUID,
    canViewAll: Boolean,
    approvableUnitIds: List<UUID>
): String {
    if (canViewAll) return ""
    if (approvableUnitIds.isEmpty()) return "AND reserved_by_user_id = '${userId}'"
    return "AND (requesting_unit_id IN (${approvableUnitIds.sqlUuidList()}) OR reserved_by_user_id = '${userId}')"
}

private fun sharedRequestVisibilitySql(
    isAdmin: Boolean,
    unitIds: List<UUID>
): String {
    if (isAdmin) return ""
    if (unitIds.isEmpty()) return "AND requested_by_user_id IS NULL"
    return "AND requesting_unit_id IN (${unitIds.sqlUuidList()})"
}

private fun requisitionVisibilitySql(
    userId: UUID,
    isTopLevelReviewer: Boolean,
    unitIds: List<UUID>
): String {
    if (isTopLevelReviewer) return ""
    if (unitIds.isEmpty()) return "AND created_by_user_id = '${userId}'"
    return "AND (organizational_unit_id IN (${unitIds.sqlUuidList()}) OR created_by_user_id = '${userId}')"
}

private fun Collection<UUID>.sqlUuidList(): String =
    joinToString(",") { "'$it'" }

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
    listOf(
        resourceState("items", tableStateSql("items", "updated_at", tuntasId)),
        resourceState("reservations", tableStateSql("reservations", "updated_at", tuntasId)),
        resourceState("requests", tableStateSql("bendras_inventory_requests", "updated_at", tuntasId)),
        resourceState("requisitions", tableStateSql("draugove_requisitions", "updated_at", tuntasId)),
        resourceState("events", tableStateSql("events", "updated_at", tuntasId)),
        resourceState("locations", tableStateSql("locations", "updated_at", tuntasId)),
        resourceState("organizational_units", tableStateSql("organizational_units", "updated_at", tuntasId)),
        resourceState(
            "members",
            stateSql(
                """
                SELECT COUNT(*)::int AS total,
                       MAX(GREATEST(users.updated_at, user_tuntas_memberships.joined_at))::text AS max_updated_at
                FROM users
                INNER JOIN user_tuntas_memberships ON users.id = user_tuntas_memberships.user_id
                WHERE user_tuntas_memberships.tuntas_id = '${tuntasId}'
                  AND user_tuntas_memberships.left_at IS NULL
                """.trimIndent()
            )
        )
    )
}

private data class ResourceStateData(
    val total: Int,
    val maxUpdatedAt: String?
)

private fun tableStateSql(table: String, updatedAtColumn: String, tuntasId: UUID): ResourceStateData =
    stateSql(
        """
        SELECT COUNT(*)::int AS total,
               MAX($updatedAtColumn)::text AS max_updated_at
        FROM $table
        WHERE tuntas_id = '${tuntasId}'
        """.trimIndent()
    )

private fun stateSql(sql: String): ResourceStateData {
    var state = ResourceStateData(total = 0, maxUpdatedAt = null)
    TransactionManager.current().exec(sql) { rs ->
        if (rs.next()) {
            state = ResourceStateData(
                total = rs.getInt("total"),
                maxUpdatedAt = rs.getString("max_updated_at")
            )
        }
    }
    return state
}

private fun resourceState(
    resource: String,
    state: ResourceStateData
): MobileCacheStateResourceResponse {
    val maxUpdatedAt = state.maxUpdatedAt
    return MobileCacheStateResourceResponse(
        resource = resource,
        maxUpdatedAt = maxUpdatedAt,
        total = state.total,
        versionKey = "$resource:${maxUpdatedAt ?: "empty"}:${state.total}"
    )
}
