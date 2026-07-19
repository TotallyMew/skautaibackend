package lt.skautai.services

import kotlinx.datetime.Clock
import lt.skautai.database.tables.BendrasInventoryRequestItems
import lt.skautai.database.tables.BendrasInventoryRequests
import lt.skautai.database.tables.ItemTransfers
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UnitAssignments
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.UserRanks
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateBendrasInventoryRequestItemRequest
import lt.skautai.models.requests.CreateBendrasInventoryRequestRequest
import lt.skautai.models.requests.DraugininkasReviewRequest
import lt.skautai.models.requests.TopLevelReviewRequest
import lt.skautai.models.responses.BendrasInventoryRequestItemResponse
import lt.skautai.models.responses.BendrasInventoryRequestCapabilitiesResponse
import lt.skautai.models.responses.BendrasInventoryRequestListResponse
import lt.skautai.models.responses.BendrasInventoryRequestResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class BendrasInventoryRequestService {

    private val unitLeaderRoles = listOf(
        "Draugininkas",
        "Draugininko pavaduotojas",
        "Gildijos pirmininkas",
        "Gildijos pirmininko pavaduotojas",
        "Vyr. skautu draugoves draugininkas",
        "Vyr. skautu draugoves draugininko pavaduotojas",
        "Vyr. skautu burelio pirmininkas",
        "Vyr. skautu burelio pirmininko pavaduotojas",
        "Vyr. skauciu draugoves draugininkas",
        "Vyr. skauciu draugoves draugininko pavaduotojas",
        "Vyr. skauciu burelio pirmininkas",
        "Vyr. skauciu burelio pirmininko pavaduotojas"
    )

    fun getAllRequests(
        tuntasId: UUID,
        userId: UUID,
        isAdmin: Boolean,
        unitIds: List<UUID>,
        updatedAfter: kotlinx.datetime.Instant? = null
    ): Result<BendrasInventoryRequestListResponse> {
        return transaction {
            var query = BendrasInventoryRequests.selectAll()
                .where { BendrasInventoryRequests.tuntasId eq tuntasId }

            when {
                isAdmin -> query = query.andWhere {
                    (BendrasInventoryRequests.requestedByUserId eq userId) or
                        (
                            (BendrasInventoryRequests.needsDraugininkasApproval eq false) or
                                (BendrasInventoryRequests.draugininkasStatus eq "FORWARDED")
                            )
                }
                unitIds.isNotEmpty() -> query = query.andWhere {
                    (BendrasInventoryRequests.requestingUnitId inList unitIds) or
                        (BendrasInventoryRequests.requestedByUserId eq userId)
                }
                else -> query = query.andWhere {
                    BendrasInventoryRequests.requestedByUserId eq userId
                }
            }
            updatedAfter?.let { since ->
                query = query.andWhere { BendrasInventoryRequests.updatedAt greater since }
            }

            val rows = query.toList()
            val hydration = buildListHydration(rows)
            val requests = rows.map { toResponse(it, hydration) }
            Result.success(BendrasInventoryRequestListResponse(requests = requests, total = requests.size))
        }
    }

    fun getRequest(requestId: UUID, tuntasId: UUID): Result<BendrasInventoryRequestResponse> {
        return transaction {
            val request = BendrasInventoryRequests.selectAll()
                .where {
                    (BendrasInventoryRequests.id eq requestId) and
                        (BendrasInventoryRequests.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            Result.success(toResponse(request))
        }
    }

    fun getRequest(
        requestId: UUID,
        tuntasId: UUID,
        userId: UUID,
        isAdmin: Boolean,
        unitIds: List<UUID>
    ): Result<BendrasInventoryRequestResponse> {
        return transaction {
            val request = BendrasInventoryRequests.selectAll()
                .where {
                    (BendrasInventoryRequests.id eq requestId) and
                        (BendrasInventoryRequests.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            val canAccess = (isAdmin && (
                request[BendrasInventoryRequests.requestedByUserId] == userId ||
                    !request[BendrasInventoryRequests.needsDraugininkasApproval] ||
                    request[BendrasInventoryRequests.draugininkasStatus] == "FORWARDED"
                )) ||
                request[BendrasInventoryRequests.requestedByUserId] == userId ||
                request[BendrasInventoryRequests.requestingUnitId]?.let { it in unitIds } == true

            if (!canAccess) {
                return@transaction Result.failure(Exception("Request is not accessible"))
            }

            val response = toResponse(request)
            val isOwner = response.requestedByUserId == userId.toString()
            val requestingUnitId = response.requestingUnitId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            Result.success(response.copy(capabilities = BendrasInventoryRequestCapabilitiesResponse(
                canReviewUnit = !isOwner && response.needsDraugininkasApproval &&
                    response.draugininkasStatus == "PENDING" && requestingUnitId in unitIds,
                canReviewTopLevel = !isOwner && isAdmin && response.topLevelStatus == "PENDING" &&
                    (!response.needsDraugininkasApproval || response.draugininkasStatus == "FORWARDED"),
                canCancel = isOwner && response.topLevelStatus == "PENDING" &&
                    response.draugininkasStatus in listOf(null, "PENDING")
            )))
        }
    }

    fun createRequest(
        tuntasId: UUID,
        requestedByUserId: UUID,
        request: CreateBendrasInventoryRequestRequest
    ): Result<BendrasInventoryRequestResponse> {
        return transaction {
            val lineRequests = normalizeLineRequests(request)
                ?: return@transaction Result.failure(Exception("Pasirink bent viena daikta is bendro inventoriaus"))

            if (lineRequests.any { it.quantity < 1 }) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            val resolvedNeededByDateInput = request.neededByDate ?: request.endDate ?: request.startDate

            val neededByDate = resolvedNeededByDateInput?.let {
                try {
                    kotlinx.datetime.LocalDate.parse(it)
                } catch (_: Exception) {
                    return@transaction Result.failure(
                        Exception("Invalid neededByDate format, use YYYY-MM-DD")
                    )
                }
            }

            val requestingUnitUUID = request.requestingUnitId?.let {
                try {
                    UUID.fromString(it)
                } catch (_: Exception) {
                    return@transaction Result.failure(Exception("Invalid requesting unit ID"))
                }
            } ?: return@transaction Result.failure(Exception("Requesting unit is required"))

            OrganizationalUnits.selectAll()
                .where {
                    (OrganizationalUnits.id eq requestingUnitUUID) and
                        (OrganizationalUnits.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Requesting unit not found in this tuntas"))

            val hasLeadershipInUnit = UserLeadershipRoles.selectAll()
                .where {
                    (UserLeadershipRoles.userId eq requestedByUserId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.organizationalUnitId eq requestingUnitUUID) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE") and
                        UserLeadershipRoles.leftAt.isNull()
                }
                .any()

            val hasMembershipInUnit = UnitAssignments.selectAll()
                .where {
                    (UnitAssignments.userId eq requestedByUserId) and
                        (UnitAssignments.tuntasId eq tuntasId) and
                        (UnitAssignments.organizationalUnitId eq requestingUnitUUID) and
                        (UnitAssignments.leftAt.isNull())
                }
                .any()

            if (!hasLeadershipInUnit && !hasMembershipInUnit) {
                return@transaction Result.failure(Exception("You can only create a request for your own unit"))
            }

            val resolvedLines = lineRequests.map { line ->
                val itemUUID = try {
                    UUID.fromString(line.itemId)
                } catch (_: Exception) {
                    return@transaction Result.failure(Exception("Invalid item ID"))
                }

                val item = Items.selectAll()
                    .where {
                        (Items.id eq itemUUID) and
                            (Items.tuntasId eq tuntasId) and
                            (Items.status eq "ACTIVE")
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Shared inventory item not found"))

                if (item[Items.custodianId] != null) {
                    return@transaction Result.failure(Exception("Only shared tuntas inventory can be requested"))
                }

                if (item[Items.quantity] < line.quantity) {
                    return@transaction Result.failure(
                        Exception("Not enough quantity for ${item[Items.name]}")
                    )
                }

                itemUUID to item
            }

            val isUnitLeader = UserLeadershipRoles
                .innerJoin(Roles)
                .selectAll()
                .where {
                    (UserLeadershipRoles.userId eq requestedByUserId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.organizationalUnitId eq requestingUnitUUID) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE") and
                        (UserLeadershipRoles.leftAt.isNull()) and
                        (Roles.name inList unitLeaderRoles)
                }
                .any()

            val isTopLevelLeader = UserLeadershipRoles
                .innerJoin(Roles)
                .selectAll()
                .where {
                    (UserLeadershipRoles.userId eq requestedByUserId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE") and
                        (UserLeadershipRoles.leftAt.isNull()) and
                        (Roles.name inList listOf("Tuntininkas", "Tuntininko pavaduotojas", "Inventorininkas"))
                }
                .any()

            val requesterRank = UserRanks
                .innerJoin(Roles)
                .selectAll()
                .where {
                    (UserRanks.userId eq requestedByUserId) and
                        (UserRanks.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?.get(Roles.name)

            val needsApproval = when {
                isTopLevelLeader || hasLeadershipInUnit || isUnitLeader -> false
                hasMembershipInUnit -> true
                requesterRank in listOf("Skautas", "Patyres skautas") -> true
                else -> request.needsDraugininkasApproval ?: false
            }

            val firstItem = resolvedLines.first().second
            val requestId = BendrasInventoryRequests.insert {
                it[this.tuntasId] = tuntasId
                it[this.requestedByUserId] = requestedByUserId
                it[this.itemId] = firstItem[Items.id]
                it[itemDescription] = request.itemDescription ?: "Keli bendro inventoriaus daiktai"
                it[quantity] = lineRequests.sumOf { line -> line.quantity }
                it[this.eventId] = null
                it[this.requestingUnitId] = requestingUnitUUID
                it[needsDraugininkasApproval] = needsApproval
                it[draugininkasStatus] = if (needsApproval) "PENDING" else null
                it[topLevelStatus] = "PENDING"
                it[this.startDate] = neededByDate
                it[this.endDate] = neededByDate
                it[notes] = request.notes
            } get BendrasInventoryRequests.id

            lineRequests.zip(resolvedLines).forEach { (lineRequest, resolved) ->
                BendrasInventoryRequestItems.insert {
                    it[BendrasInventoryRequestItems.requestId] = requestId
                    it[BendrasInventoryRequestItems.itemId] = resolved.first
                    it[BendrasInventoryRequestItems.quantity] = lineRequest.quantity
                }
            }

            val inserted = BendrasInventoryRequests.selectAll()
                .where { BendrasInventoryRequests.id eq requestId }
                .first()

            Result.success(toResponse(inserted))
        }
    }

    fun cancelRequest(
        requestId: UUID,
        tuntasId: UUID,
        requestingUserId: UUID
    ): Result<Unit> {
        return transaction {
            val existing = BendrasInventoryRequests.selectAll()
                .where {
                    (BendrasInventoryRequests.id eq requestId) and
                        (BendrasInventoryRequests.tuntasId eq tuntasId)
                }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (existing[BendrasInventoryRequests.requestedByUserId] != requestingUserId) {
                return@transaction Result.failure(Exception("You can only cancel your own requests"))
            }

            val topStatus = existing[BendrasInventoryRequests.topLevelStatus]
            val draugininkasStatus = existing[BendrasInventoryRequests.draugininkasStatus]
            val cancellable = topStatus == "PENDING" &&
                (draugininkasStatus == null || draugininkasStatus == "PENDING")

            if (!cancellable) {
                return@transaction Result.failure(Exception("Request cannot be cancelled in its current state"))
            }

            BendrasInventoryRequests.update({
                (BendrasInventoryRequests.id eq requestId) and
                    (BendrasInventoryRequests.tuntasId eq tuntasId)
            }) {
                it[BendrasInventoryRequests.topLevelStatus] = "REJECTED"
                it[topLevelRejectionReason] = "Cancelled by requester"
            }

            Result.success(Unit)
        }
    }

    fun draugininkasReview(
        requestId: UUID,
        tuntasId: UUID,
        reviewerUserId: UUID,
        request: DraugininkasReviewRequest
    ): Result<BendrasInventoryRequestResponse> {
        return transaction {
            if (request.action !in listOf("FORWARDED", "REJECTED")) {
                return@transaction Result.failure(Exception("Action must be FORWARDED or REJECTED"))
            }

            val existing = BendrasInventoryRequests.selectAll()
                .where {
                    (BendrasInventoryRequests.id eq requestId) and
                        (BendrasInventoryRequests.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (!existing[BendrasInventoryRequests.needsDraugininkasApproval]) {
                return@transaction Result.failure(Exception("This request does not require unit leader approval"))
            }

            if (existing[BendrasInventoryRequests.requestedByUserId] == reviewerUserId) {
                return@transaction Result.failure(Exception("You cannot review your own shared inventory request"))
            }

            if (existing[BendrasInventoryRequests.draugininkasStatus] != "PENDING") {
                return@transaction Result.failure(Exception("Request is not pending unit leader review"))
            }

            val requestingUnitId = existing[BendrasInventoryRequests.requestingUnitId]
                ?: return@transaction Result.failure(Exception("Request has no unit assigned"))

            val isReviewerUnitLeader = UserLeadershipRoles
                .innerJoin(Roles)
                .selectAll()
                .where {
                    (UserLeadershipRoles.userId eq reviewerUserId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE") and
                        (UserLeadershipRoles.leftAt.isNull()) and
                        (UserLeadershipRoles.organizationalUnitId eq requestingUnitId) and
                        (Roles.name inList unitLeaderRoles)
                }
                .any()

            if (!isReviewerUnitLeader) {
                return@transaction Result.failure(Exception("You are not a unit leader of this request's unit"))
            }

            BendrasInventoryRequests.update({
                (BendrasInventoryRequests.id eq requestId) and
                    (BendrasInventoryRequests.tuntasId eq tuntasId)
            }) {
                it[draugininkasStatus] = request.action
                it[draugininkasReviewedByUserId] = reviewerUserId
                it[draugininkasRejectionReason] = request.rejectionReason
                if (request.action == "REJECTED") {
                    it[topLevelStatus] = "REJECTED"
                    it[topLevelRejectionReason] = "Rejected by unit leader"
                }
            }

            val updated = BendrasInventoryRequests.selectAll()
                .where { BendrasInventoryRequests.id eq requestId }
                .first()

            Result.success(toResponse(updated))
        }
    }

    fun topLevelReview(
        requestId: UUID,
        tuntasId: UUID,
        reviewerUserId: UUID,
        request: TopLevelReviewRequest
    ): Result<BendrasInventoryRequestResponse> {
        return transaction {
            if (request.action !in listOf("APPROVED", "REJECTED")) {
                return@transaction Result.failure(Exception("Action must be APPROVED or REJECTED"))
            }

            val existing = BendrasInventoryRequests.selectAll()
                .where {
                    (BendrasInventoryRequests.id eq requestId) and
                        (BendrasInventoryRequests.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (existing[BendrasInventoryRequests.requestedByUserId] == reviewerUserId) {
                return@transaction Result.failure(Exception("You cannot review your own shared inventory request"))
            }

            if (existing[BendrasInventoryRequests.topLevelStatus] != "PENDING") {
                return@transaction Result.failure(Exception("Request is not pending top level review"))
            }

            if (
                existing[BendrasInventoryRequests.needsDraugininkasApproval] &&
                existing[BendrasInventoryRequests.draugininkasStatus] != "FORWARDED"
            ) {
                return@transaction Result.failure(Exception("Request must be forwarded by unit leader first"))
            }

            val requestLines = loadRequestItems(existing)
            if (request.action == "APPROVED") {
                val requestingUnitId = existing[BendrasInventoryRequests.requestingUnitId]
                    ?: return@transaction Result.failure(Exception("Requesting unit missing"))

                requestLines.forEach { line ->
                    val sharedItem = Items.selectAll()
                        .where {
                            (Items.id eq UUID.fromString(line.itemId)) and
                                (Items.tuntasId eq tuntasId)
                        }
                        .forUpdate()
                        .firstOrNull()
                        ?: return@transaction Result.failure(Exception("Shared item not found"))

                    val availableQuantity = sharedItem[Items.quantity]
                    if (sharedItem[Items.custodianId] != null) {
                        return@transaction Result.failure(Exception("Only shared inventory items can be transferred"))
                    }
                    if (availableQuantity < line.quantity) {
                        return@transaction Result.failure(
                            Exception("Not enough quantity left for ${sharedItem[Items.name]}")
                        )
                    }

                    val remaining = availableQuantity - line.quantity
                    Items.update({ Items.id eq sharedItem[Items.id] }) {
                        it[quantity] = remaining
                        it[status] = if (remaining == 0) "INACTIVE" else "ACTIVE"
                    }
                    InventoryKitService.syncMembershipAfterItemQuantityChange(sharedItem[Items.id], remaining)

                    val existingUnitItem = Items.selectAll()
                        .where {
                            (Items.tuntasId eq tuntasId) and
                                (Items.custodianId eq requestingUnitId) and
                                (Items.sourceSharedItemId eq sharedItem[Items.id]) and
                                (Items.status eq "ACTIVE")
                        }
                        .firstOrNull()

                    val unitItemId = if (existingUnitItem != null) {
                        Items.update({ Items.id eq existingUnitItem[Items.id] }) {
                            it[quantity] = existingUnitItem[Items.quantity] + line.quantity
                        }
                        existingUnitItem[Items.id]
                    } else {
                        Items.insert {
                            it[this.tuntasId] = tuntasId
                            it[custodianId] = requestingUnitId
                            it[origin] = "TRANSFERRED_FROM_TUNTAS"
                            it[name] = sharedItem[Items.name]
                            it[description] = sharedItem[Items.description]
                            it[type] = "COLLECTIVE"
                            it[category] = sharedItem[Items.category]
                            it[condition] = sharedItem[Items.condition]
                            it[quantity] = line.quantity
                            it[locationId] = null
                            it[temporaryStorageLabel] = null
                            it[sourceSharedItemId] = sharedItem[Items.id]
                            it[createdByUserId] = reviewerUserId
                            it[photoUrl] = sharedItem[Items.photoUrl]
                            it[purchaseDate] = sharedItem[Items.purchaseDate]
                            it[purchasePrice] = sharedItem[Items.purchasePrice]
                            it[notes] = sharedItem[Items.notes]
                            it[status] = "ACTIVE"
                        } get Items.id
                    }

                    ItemTransfers.insert {
                        it[itemId] = sharedItem[Items.id]
                        it[fromCustodianId] = null
                        it[toCustodianId] = requestingUnitId
                        it[initiatedByUserId] = existing[BendrasInventoryRequests.requestedByUserId]
                        it[approvedByUserId] = reviewerUserId
                        it[notes] = existing[BendrasInventoryRequests.notes]
                        it[status] = "COMPLETED"
                        it[completedAt] = Clock.System.now()
                    }
                    val now = Clock.System.now()
                    ItemService.recordItemHistory(
                        itemId = sharedItem[Items.id],
                        eventType = "TRANSFERRED_TO_UNIT",
                        quantityChange = -line.quantity,
                        performedByUserId = reviewerUserId,
                        notes = existing[BendrasInventoryRequests.notes] ?: "Perduota pagal paemimo prasyma",
                        createdAt = now
                    )
                    ItemService.recordItemHistory(
                        itemId = unitItemId,
                        eventType = "RECEIVED_FROM_SHARED",
                        quantityChange = line.quantity,
                        performedByUserId = reviewerUserId,
                        notes = existing[BendrasInventoryRequests.notes] ?: "Gauta pagal paemimo prasyma",
                        createdAt = now
                    )
                }
            }

            BendrasInventoryRequests.update({
                (BendrasInventoryRequests.id eq requestId) and
                    (BendrasInventoryRequests.tuntasId eq tuntasId)
            }) {
                it[topLevelStatus] = request.action
                it[topLevelReviewedByUserId] = reviewerUserId
                it[topLevelRejectionReason] = request.rejectionReason
            }

            val updated = BendrasInventoryRequests.selectAll()
                .where { BendrasInventoryRequests.id eq requestId }
                .first()

            Result.success(toResponse(updated))
        }
    }

    private fun normalizeLineRequests(
        request: CreateBendrasInventoryRequestRequest
    ): List<CreateBendrasInventoryRequestItemRequest>? {
        return when {
            request.items.isNotEmpty() -> request.items
            request.itemId != null -> listOf(
                CreateBendrasInventoryRequestItemRequest(
                    itemId = request.itemId,
                    quantity = request.quantity
                )
            )
            else -> null
        }
    }

    private data class BendrasRequestListHydration(
        val requestItemsByRequestId: Map<UUID, List<BendrasInventoryRequestItemResponse>>,
        val itemNamesById: Map<UUID, String>,
        val unitNamesById: Map<UUID, String>,
        val userNamesById: Map<UUID, String>
    )

    private fun buildListHydration(rows: List<ResultRow>): BendrasRequestListHydration {
        if (rows.isEmpty()) {
            return BendrasRequestListHydration(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }

        val requestIds = rows.map { it[BendrasInventoryRequests.id] }
        val persistedLines = BendrasInventoryRequestItems.selectAll()
            .where { BendrasInventoryRequestItems.requestId inList requestIds }
            .toList()
        val itemIds = (
            persistedLines.map { it[BendrasInventoryRequestItems.itemId] } +
                rows.mapNotNull { it[BendrasInventoryRequests.itemId] }
            ).toSet()

        val itemNamesById = if (itemIds.isEmpty()) {
            emptyMap()
        } else {
            Items.selectAll()
                .where { Items.id inList itemIds.toList() }
                .associate { it[Items.id] to it[Items.name] }
        }

        val requestItemsByRequestId = persistedLines
            .groupBy { it[BendrasInventoryRequestItems.requestId] }
            .mapValues { (_, lines) ->
                lines.map { line ->
                    BendrasInventoryRequestItemResponse(
                        id = line[BendrasInventoryRequestItems.id].toString(),
                        itemId = line[BendrasInventoryRequestItems.itemId].toString(),
                        itemName = itemNamesById[line[BendrasInventoryRequestItems.itemId]] ?: "Unknown",
                        quantity = line[BendrasInventoryRequestItems.quantity]
                    )
                }
            }

        val unitIds = rows.mapNotNull { it[BendrasInventoryRequests.requestingUnitId] }.toSet()
        val unitNamesById = if (unitIds.isEmpty()) {
            emptyMap()
        } else {
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id inList unitIds.toList() }
                .associate { it[OrganizationalUnits.id] to it[OrganizationalUnits.name] }
        }

        val userIds = rows.map { it[BendrasInventoryRequests.requestedByUserId] }.toSet()
        val userNamesById = Users.selectAll()
            .where { (Users.id inList userIds.toList()) and Users.deletedAt.isNull() }
            .associate { it[Users.id] to "${it[Users.name]} ${it[Users.surname]}".trim() }

        return BendrasRequestListHydration(
            requestItemsByRequestId = requestItemsByRequestId,
            itemNamesById = itemNamesById,
            unitNamesById = unitNamesById,
            userNamesById = userNamesById
        )
    }

    private fun loadRequestItems(row: ResultRow, hydration: BendrasRequestListHydration? = null): List<BendrasInventoryRequestItemResponse> {
        hydration?.requestItemsByRequestId?.get(row[BendrasInventoryRequests.id])?.takeIf { it.isNotEmpty() }?.let { return it }

        val persisted = BendrasInventoryRequestItems.selectAll()
            .where { BendrasInventoryRequestItems.requestId eq row[BendrasInventoryRequests.id] }
            .map { line ->
                val item = Items.selectAll()
                    .where { Items.id eq line[BendrasInventoryRequestItems.itemId] }
                    .firstOrNull()
                BendrasInventoryRequestItemResponse(
                    id = line[BendrasInventoryRequestItems.id].toString(),
                    itemId = line[BendrasInventoryRequestItems.itemId].toString(),
                    itemName = item?.get(Items.name) ?: "Unknown",
                    quantity = line[BendrasInventoryRequestItems.quantity]
                )
            }

        if (persisted.isNotEmpty()) return persisted

        val legacyItemId = row[BendrasInventoryRequests.itemId] ?: return emptyList()
        hydration?.itemNamesById?.get(legacyItemId)?.let { legacyName ->
            return listOf(
                BendrasInventoryRequestItemResponse(
                    id = row[BendrasInventoryRequests.id].toString(),
                    itemId = legacyItemId.toString(),
                    itemName = legacyName,
                    quantity = row[BendrasInventoryRequests.quantity]
                )
            )
        }
        val legacyName = Items.selectAll()
            .where { Items.id eq legacyItemId }
            .firstOrNull()
            ?.get(Items.name)
            ?: "Unknown"

        return listOf(
            BendrasInventoryRequestItemResponse(
                id = row[BendrasInventoryRequests.id].toString(),
                itemId = legacyItemId.toString(),
                itemName = legacyName,
                quantity = row[BendrasInventoryRequests.quantity]
            )
        )
    }

    private fun toResponse(row: ResultRow, hydration: BendrasRequestListHydration? = null): BendrasInventoryRequestResponse {
        val items = loadRequestItems(row, hydration)
        val itemName = when {
            items.size > 1 -> "Keli bendro inventoriaus daiktai"
            items.isNotEmpty() -> items.first().itemName
            else -> row[BendrasInventoryRequests.itemDescription] ?: "Unknown"
        }

        val requestingUnitId = row[BendrasInventoryRequests.requestingUnitId]
        val requestingUnitName = requestingUnitId?.let {
            hydration?.unitNamesById?.get(it) ?:
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id eq it }
                .firstOrNull()
                ?.get(OrganizationalUnits.name)
        }
        val requestedByUserName = hydration?.userNamesById?.get(row[BendrasInventoryRequests.requestedByUserId])
            ?: Users.selectAll()
                .where { (Users.id eq row[BendrasInventoryRequests.requestedByUserId]) and Users.deletedAt.isNull() }
                .firstOrNull()
                ?.let { "${it[Users.name]} ${it[Users.surname]}".trim() }

        val neededByDate = row[BendrasInventoryRequests.startDate]?.toString()

        return BendrasInventoryRequestResponse(
            id = row[BendrasInventoryRequests.id].toString(),
            tuntasId = row[BendrasInventoryRequests.tuntasId].toString(),
            requestedByUserId = row[BendrasInventoryRequests.requestedByUserId].toString(),
            requestedByUserName = requestedByUserName,
            itemId = row[BendrasInventoryRequests.itemId]?.toString(),
            itemName = itemName,
            itemDescription = row[BendrasInventoryRequests.itemDescription],
            quantity = items.sumOf { it.quantity }.takeIf { it > 0 } ?: row[BendrasInventoryRequests.quantity],
            neededByDate = neededByDate,
            requestingUnitId = requestingUnitId?.toString(),
            requestingUnitName = requestingUnitName,
            needsDraugininkasApproval = row[BendrasInventoryRequests.needsDraugininkasApproval],
            draugininkasStatus = row[BendrasInventoryRequests.draugininkasStatus],
            draugininkasReviewedByUserId = row[BendrasInventoryRequests.draugininkasReviewedByUserId]?.toString(),
            draugininkasRejectionReason = row[BendrasInventoryRequests.draugininkasRejectionReason],
            topLevelStatus = row[BendrasInventoryRequests.topLevelStatus],
            topLevelReviewedByUserId = row[BendrasInventoryRequests.topLevelReviewedByUserId]?.toString(),
            topLevelRejectionReason = row[BendrasInventoryRequests.topLevelRejectionReason],
            notes = row[BendrasInventoryRequests.notes],
            items = items,
            createdAt = row[BendrasInventoryRequests.createdAt].toString(),
            updatedAt = row[BendrasInventoryRequests.updatedAt].toString()
        )
    }
}
