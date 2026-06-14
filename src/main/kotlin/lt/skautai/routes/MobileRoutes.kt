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
import lt.skautai.services.PermissionContext
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

                    HomeSummaryCounts(
                        activeUnitItemCount = resolvedUnit?.id?.let { unitId ->
                            countSql(
                                """
                                SELECT COUNT(*)
                                FROM items
                                WHERE tuntas_id = '${tuntasId}'
                                  AND status = 'ACTIVE'
                                  AND custodian_id = '${unitId}'
                                  AND type <> 'INDIVIDUAL'
                                  $itemVisibility
                                """.trimIndent()
                            )
                        } ?: 0,
                        activeUnitFromSharedCount = resolvedUnit?.id?.let { unitId ->
                            countSql(
                                """
                                SELECT COUNT(*)
                                FROM items
                                WHERE tuntas_id = '${tuntasId}'
                                  AND status = 'ACTIVE'
                                  AND custodian_id = '${unitId}'
                                  AND origin = 'TRANSFERRED_FROM_TUNTAS'
                                  $itemVisibility
                                """.trimIndent()
                            )
                        } ?: 0,
                        sharedInventoryCount = countSql(
                            """
                            SELECT COUNT(*)
                            FROM items
                            WHERE tuntas_id = '${tuntasId}'
                              AND status = 'ACTIVE'
                              AND custodian_id IS NULL
                              AND type <> 'INDIVIDUAL'
                              $itemVisibility
                            """.trimIndent()
                        ),
                        sharedPendingApprovalCount = if (permissions.has("items.review") || permissions.has("items.create")) {
                            countSql(
                                """
                                SELECT COUNT(*)
                                FROM items
                                WHERE tuntas_id = '${tuntasId}'
                                  AND status = 'PENDING_APPROVAL'
                                  AND custodian_id IS NULL
                                  AND type <> 'INDIVIDUAL'
                                  $itemVisibility
                                """.trimIndent()
                            )
                        } else 0,
                        personalLendingCount = countSql(
                            """
                            SELECT COUNT(*)
                            FROM items
                            WHERE tuntas_id = '${tuntasId}'
                              AND status = 'ACTIVE'
                              AND type = 'INDIVIDUAL'
                              AND created_by_user_id = '${userId}'
                              $itemVisibility
                            """.trimIndent()
                        ),
                        requisitionCount = countSql(
                            """
                            SELECT COUNT(*)
                            FROM draugove_requisitions
                            WHERE tuntas_id = '${tuntasId}'
                              $requisitionVisibility
                            """.trimIndent()
                        ),
                        myRequisitionCount = countSql(
                            """
                            SELECT COUNT(*)
                            FROM draugove_requisitions
                            WHERE tuntas_id = '${tuntasId}'
                              AND created_by_user_id = '${userId}'
                              $requisitionVisibility
                            """.trimIndent()
                        ),
                        assignedRequisitionCount = if (canReviewRequisitions) {
                            countSql(
                                """
                                SELECT COUNT(*)
                                FROM draugove_requisitions
                                WHERE tuntas_id = '${tuntasId}'
                                  AND created_by_user_id <> '${userId}'
                                  AND top_level_review_status = 'PENDING'
                                  $requisitionVisibility
                                """.trimIndent()
                            )
                        } else 0,
                        sharedRequestCount = countSql(
                            """
                            SELECT COUNT(*)
                            FROM bendras_inventory_requests
                            WHERE tuntas_id = '${tuntasId}'
                              AND top_level_status = 'PENDING'
                              $sharedRequestVisibility
                            """.trimIndent()
                        ),
                        myReservationCount = countSql(
                            """
                            SELECT COUNT(DISTINCT group_id)
                            FROM reservations
                            WHERE tuntas_id = '${tuntasId}'
                              AND reserved_by_user_id = '${userId}'
                              AND status IN ('APPROVED', 'ACTIVE')
                              $reservationVisibility
                            """.trimIndent()
                        ),
                        assignedReservationCount = if (canReviewReservations) {
                            countSql(
                                """
                                SELECT COUNT(DISTINCT group_id)
                                FROM reservations
                                WHERE tuntas_id = '${tuntasId}'
                                  AND status = 'PENDING'
                                  $reservationVisibility
                                """.trimIndent()
                            )
                        } else 0,
                        trackedReservationCount = if (canReviewReservations) {
                            countSql(
                                """
                                SELECT COUNT(DISTINCT group_id)
                                FROM reservations
                                WHERE tuntas_id = '${tuntasId}'
                                  AND status IN ('APPROVED', 'ACTIVE')
                                  $reservationVisibility
                                """.trimIndent()
                            )
                        } else 0
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
