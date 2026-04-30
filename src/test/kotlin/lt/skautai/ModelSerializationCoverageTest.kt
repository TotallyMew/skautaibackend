package lt.skautai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import lt.skautai.models.requests.*
import lt.skautai.models.responses.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModelSerializationCoverageTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    private inline fun <reified T> assertRoundTrip(value: T) {
        val encoded = json.encodeToString(value)
        assertEquals(value, json.decodeFromString<T>(encoded))
    }

    @Test
    fun `event request models round trip through json`() {
        assertRoundTrip(
            CreateEventRequest(
                name = "Rudens zygeiviu stovykla",
                type = "STOVYKLA",
                startDate = "2026-07-01T10:00:00Z",
                endDate = "2026-07-07T18:00:00Z",
                locationId = "loc-1",
                organizationalUnitId = "unit-1",
                notes = "Pastabos"
            )
        )
        assertRoundTrip(
            UpdateEventRequest(
                name = "Atnaujintas pavadinimas",
                startDate = "2026-07-02T10:00:00Z",
                endDate = "2026-07-08T18:00:00Z",
                locationId = "loc-2",
                organizationalUnitId = "unit-2",
                notes = "Atnaujintos pastabos",
                status = "ACTIVE"
            )
        )
        assertRoundTrip(AssignEventRoleRequest(userId = "user-1", role = "VADOVAS", targetGroup = "group-a"))
        assertRoundTrip(CreatePastovykleRequest(name = "Vilku pastovykle", responsibleUserId = "user-2", ageGroup = "10-12", notes = "A"))
        assertRoundTrip(UpdatePastovykleRequest(name = "Skautu pastovykle", responsibleUserId = "user-3", ageGroup = "13-15", notes = "B"))
        assertRoundTrip(AssignPastovykleInventoryRequest(itemId = "item-1", quantity = 3, recipientUserId = "user-4", recipientType = "USER", notes = "C"))
        assertRoundTrip(UpdatePastovykleInventoryRequest(quantityReturned = 2, returnedAt = "2026-07-09T12:00:00Z", notes = "D"))
        assertRoundTrip(CreateEventInventoryBucketRequest(name = "Pagrindinis sandelis", type = "LOCATION", pastovykleId = "camp-1", locationId = "loc-3", notes = "E"))
        assertRoundTrip(UpdateEventInventoryBucketRequest(name = "Atsarginis sandelis", type = "PASTOVYKLE", pastovykleId = "camp-2", locationId = "loc-4", notes = "F"))
        assertRoundTrip(CreateEventInventoryItemRequest(itemId = "item-2", name = "Palapine", plannedQuantity = 8, bucketId = "bucket-1", responsibleUserId = "user-5", notes = "G"))
        assertRoundTrip(
            CreateEventInventoryItemsBulkRequest(
                items = listOf(
                    CreateEventInventoryItemRequest(name = "Virve", plannedQuantity = 2),
                    CreateEventInventoryItemRequest(itemId = "item-3", name = "Puodas", plannedQuantity = 1, notes = "H")
                )
            )
        )
        assertRoundTrip(UpdateEventInventoryItemRequest(name = "Palapine XXL", plannedQuantity = 10, bucketId = "bucket-2", responsibleUserId = "user-6", notes = "I"))
        assertRoundTrip(CreateEventInventoryAllocationRequest(eventInventoryItemId = "event-item-1", bucketId = "bucket-3", quantity = 4, notes = "J"))
        assertRoundTrip(UpdateEventInventoryAllocationRequest(quantity = 5, notes = "K"))
        assertRoundTrip(CreateEventPurchaseItemRequest(eventInventoryItemId = "event-item-2", purchasedQuantity = 6, unitPrice = 12.5, notes = "L"))
        assertRoundTrip(
            CreateEventPurchaseRequest(
                purchaseDate = "2026-07-03T09:00:00Z",
                notes = "M",
                items = listOf(CreateEventPurchaseItemRequest(eventInventoryItemId = "event-item-3", purchasedQuantity = 2))
            )
        )
        assertRoundTrip(UpdateEventPurchaseRequest(status = "APPROVED", purchaseDate = "2026-07-04T09:00:00Z", totalAmount = 99.99, invoiceFileUrl = "/files/invoice.pdf", notes = "N"))
        assertRoundTrip(AttachEventPurchaseInvoiceRequest(invoiceFileUrl = "/files/invoice-2.pdf"))
        assertRoundTrip(CreateEventInventoryMovementRequest(eventInventoryItemId = "event-item-4", movementType = "OUT", quantity = 7, pastovykleId = "camp-3", toUserId = "user-7", fromCustodyId = "custody-1", requestId = "request-1", notes = "O"))
        assertRoundTrip(CreatePastovykleInventoryRequestRequest(eventInventoryItemId = "event-item-5", quantity = 9, notes = "P"))
        assertRoundTrip(FulfillPastovykleInventoryRequestRequest(quantity = 3, notes = "Q"))
        assertRoundTrip(MarkPastovykleInventoryRequestSelfProvidedRequest(notes = "R"))
        assertRoundTrip(AssignUnitInventoryToPastovykleRequest(itemId = "item-4", quantity = 2, notes = "S"))
        assertRoundTrip(ReconcileEventReturnLineRequest(custodyId = "custody-2", decision = "RETURN_TO_STORAGE", quantity = 2, notes = "T"))
        assertRoundTrip(
            ReconcileEventReturnsRequest(
                returns = listOf(
                    ReconcileEventReturnLineRequest(custodyId = "custody-3", decision = "RETURN_TO_STORAGE", quantity = 1)
                )
            )
        )
        assertRoundTrip(ReconcileEventPurchaseLineRequest(purchaseItemId = "purchase-item-1", decision = "ADD_TO_EXISTING", quantity = 1, existingItemId = "item-5", name = "Kibirai", notes = "U"))
        assertRoundTrip(
            ReconcileEventPurchasesRequest(
                purchases = listOf(
                    ReconcileEventPurchaseLineRequest(purchaseItemId = "purchase-item-2", decision = "CREATE_NEW", quantity = 4, name = "Naujas daiktas")
                )
            )
        )
    }

    @Test
    fun `event response models round trip through json`() {
        val role = EventRoleResponse(
            id = "role-1",
            userId = "user-1",
            userName = "Vardenis Pavardenis",
            role = "KOORDINATORIUS",
            targetGroup = "group-b",
            assignedByUserId = "user-2",
            assignedAt = "2026-06-01T08:00:00Z"
        )
        val bucket = EventInventoryBucketResponse(
            id = "bucket-1",
            eventId = "event-1",
            name = "Sandelis",
            type = "LOCATION",
            pastovykleId = "camp-1",
            pastovykleName = "Vilku",
            locationId = "loc-1",
            locationPath = "Sandelis/Aukstas 1",
            notes = "A"
        )
        val item = EventInventoryItemResponse(
            id = "event-item-1",
            eventId = "event-1",
            itemId = "item-1",
            bucketId = "bucket-1",
            bucketName = "Sandelis",
            reservationGroupId = "reservation-group-1",
            name = "Puodas",
            plannedQuantity = 5,
            availableQuantity = 4,
            shortageQuantity = 1,
            allocatedQuantity = 3,
            unallocatedQuantity = 2,
            needsPurchase = true,
            notes = "B",
            responsibleUserId = "user-3",
            responsibleUserName = "Atsakingas",
            createdByUserId = "user-4",
            createdAt = "2026-06-01T09:00:00Z"
        )
        val allocation = EventInventoryAllocationResponse(
            id = "alloc-1",
            eventInventoryItemId = "event-item-1",
            bucketId = "bucket-2",
            bucketName = "Virtuve",
            quantity = 2,
            notes = "C"
        )
        val purchaseItem = EventPurchaseItemResponse(
            id = "purchase-line-1",
            purchaseId = "purchase-1",
            eventInventoryItemId = "event-item-1",
            itemName = "Puodas",
            purchasedQuantity = 2,
            unitPrice = 19.5,
            lineTotal = 39.0,
            addedToInventory = true,
            addedToInventoryItemId = "item-2",
            notes = "D"
        )
        val custody = EventInventoryCustodyResponse(
            id = "custody-1",
            eventInventoryItemId = "event-item-1",
            itemName = "Puodas",
            pastovykleId = "camp-1",
            pastovykleName = "Vilku",
            holderUserId = "user-5",
            holderUserName = "Turėtojas",
            quantity = 4,
            returnedQuantity = 1,
            remainingQuantity = 3,
            status = "ACTIVE",
            createdByUserId = "user-4",
            createdByUserName = "Kurejas",
            createdAt = "2026-06-01T10:00:00Z",
            closedAt = null,
            notes = "E"
        )
        val movement = EventInventoryMovementResponse(
            id = "move-1",
            eventId = "event-1",
            eventInventoryItemId = "event-item-1",
            itemName = "Puodas",
            custodyId = "custody-1",
            movementType = "TRANSFER",
            quantity = 1,
            fromPastovykleId = "camp-1",
            fromPastovykleName = "Vilku",
            toPastovykleId = "camp-2",
            toPastovykleName = "Skautu",
            fromUserId = "user-5",
            fromUserName = "Turėtojas",
            toUserId = "user-6",
            toUserName = "Gavėjas",
            performedByUserId = "user-4",
            performedByUserName = "Kurejas",
            notes = "F",
            createdAt = "2026-06-01T11:00:00Z"
        )
        val request = EventInventoryRequestResponse(
            id = "request-1",
            eventId = "event-1",
            eventInventoryItemId = "event-item-1",
            itemId = "item-1",
            itemName = "Puodas",
            pastovykleId = "camp-1",
            pastovykleName = "Vilku",
            requestedByUserId = "user-7",
            requestedByName = "Prasantis",
            quantity = 2,
            status = "OPEN",
            notes = "G",
            createdAt = "2026-06-01T12:00:00Z",
            reviewedAt = "2026-06-01T13:00:00Z",
            reviewedByUserId = "user-8",
            reviewedByUserName = "Perziurejes",
            fulfilledAt = "2026-06-01T14:00:00Z",
            resolvedByUserId = "user-9",
            resolvedByUserName = "Išsprendė"
        )
        val returnLine = EventReconciliationReturnLineResponse(
            custodyId = "custody-1",
            eventInventoryItemId = "event-item-1",
            itemId = "item-1",
            itemName = "Puodas",
            pastovykleId = "camp-1",
            pastovykleName = "Vilku",
            holderUserId = "user-5",
            holderUserName = "Turėtojas",
            quantity = 4,
            returnedQuantity = 2,
            remainingQuantity = 2,
            status = "PARTIAL",
            notes = "H"
        )
        val purchaseLine = EventReconciliationPurchaseLineResponse(
            purchaseId = "purchase-1",
            purchaseItemId = "purchase-line-1",
            eventInventoryItemId = "event-item-1",
            itemId = "item-2",
            itemName = "Puodas",
            purchasedQuantity = 2,
            status = "PENDING",
            invoiceFileUrl = "/files/invoice.pdf",
            notes = "I"
        )

        assertRoundTrip(EventListResponse(events = emptyList(), total = 0))
        assertRoundTrip(
            EventResponse(
                id = "event-1",
                tuntasId = "tuntas-1",
                name = "Renginys",
                type = "STOVYKLA",
                startDate = "2026-07-01T10:00:00Z",
                endDate = "2026-07-07T18:00:00Z",
                locationId = "loc-1",
                organizationalUnitId = "unit-1",
                createdByUserId = "user-1",
                status = "ACTIVE",
                notes = "J",
                createdAt = "2026-06-01T07:00:00Z",
                eventRoles = listOf(role),
                inventorySummary = EventInventorySummaryResponse(10, 8, 2, 6, 1)
            )
        )
        assertRoundTrip(PastovykleResponse(id = "camp-1", eventId = "event-1", name = "Vilku", responsibleUserId = "user-2", ageGroup = "10-12", notes = "K"))
        assertRoundTrip(PastovykleListResponse(pastovykles = listOf(PastovykleResponse(id = "camp-2", eventId = "event-1", name = "Skautu")), total = 1))
        assertRoundTrip(PastovykleInventoryResponse(id = "pi-1", pastovykleId = "camp-1", itemId = "item-1", itemName = "Puodas", distributedByUserId = "user-2", recipientUserId = "user-3", recipientType = "USER", quantityAssigned = 4, quantityReturned = 2, assignedAt = "2026-06-01T15:00:00Z", returnedAt = "2026-06-02T15:00:00Z", notes = "L"))
        assertRoundTrip(PastovykleInventoryListResponse(inventory = emptyList(), total = 0))
        assertRoundTrip(bucket)
        assertRoundTrip(item)
        assertRoundTrip(allocation)
        assertRoundTrip(EventInventoryPlanResponse(buckets = listOf(bucket), items = listOf(item), allocations = listOf(allocation)))
        assertRoundTrip(EventInventoryItemListResponse(items = listOf(item), total = 1))
        assertRoundTrip(EventInventorySummaryResponse(totalPlannedQuantity = 10, totalAvailableQuantity = 8, totalShortageQuantity = 2, totalAllocatedQuantity = 6, itemsNeedingPurchase = 1))
        assertRoundTrip(EventPurchaseResponse(id = "purchase-1", eventId = "event-1", purchasedByUserId = "user-1", purchasedByName = "Pirkėjas", status = "OPEN", purchaseDate = "2026-07-03T09:00:00Z", totalAmount = 39.0, invoiceFileUrl = "/files/invoice.pdf", notes = "M", createdAt = "2026-06-01T16:00:00Z", updatedAt = "2026-06-01T17:00:00Z", items = listOf(purchaseItem)))
        assertRoundTrip(EventPurchaseListResponse(purchases = listOf(), total = 0))
        assertRoundTrip(custody)
        assertRoundTrip(movement)
        assertRoundTrip(EventInventoryCustodyListResponse(custody = listOf(custody), total = 1))
        assertRoundTrip(EventInventoryMovementListResponse(movements = listOf(movement), total = 1))
        assertRoundTrip(request)
        assertRoundTrip(EventInventoryRequestListResponse(requests = listOf(request), total = 1))
        assertRoundTrip(returnLine)
        assertRoundTrip(purchaseLine)
        assertRoundTrip(
            EventReconciliationResponse(
                eventId = "event-1",
                status = "IN_PROGRESS",
                openReturns = listOf(returnLine),
                returnedToEventStorage = emptyList(),
                unresolvedPurchases = listOf(purchaseLine),
                canComplete = false
            )
        )
    }

    @Test
    fun `auth member requisition and reservation request models round trip through json`() {
        assertRoundTrip(RegisterTuntininkasRequest(name = "Jonas", surname = "Jonaitis", email = "jonas@test.com", password = "testas123", phone = "+37060000000", tuntasName = "Vilniaus tuntas", tuntasKrastas = "Vilniaus", tuntasContactEmail = "kontaktai@test.com"))
        assertRoundTrip(RegisterWithInviteRequest(name = "Petras", surname = "Petraitis", email = "petras@test.com", password = "testas123", phone = "+37061111111", inviteCode = "CODE123"))
        assertRoundTrip(LoginRequest(email = "prisijungimas@test.com", password = "slaptazodis"))
        assertRoundTrip(RefreshTokenRequest(refreshToken = "refresh-token"))
        assertRoundTrip(AssignLeadershipRoleRequest(roleId = "role-1", organizationalUnitId = "unit-1", startsAt = "2026-01-01T00:00:00Z", expiresAt = "2026-12-31T23:59:59Z", termNumber = 2))
        assertRoundTrip(UpdateLeadershipRoleRequest(startsAt = "2026-02-01T00:00:00Z", expiresAt = "2026-11-30T23:59:59Z", termStatus = "ACTIVE", organizationalUnitId = "unit-2"))
        assertRoundTrip(TransferTuntininkasRequest(successorUserId = "user-1"))
        assertRoundTrip(AssignRankRequest(roleId = "role-2"))
        assertRoundTrip(CreateRequisitionItemRequest(itemName = "Kirvis", itemDescription = "Mazas", quantity = 2, notes = "A"))
        assertRoundTrip(CreateRequisitionRequest(requestingUnitId = "unit-3", neededByDate = "2026-08-01", notes = "B", items = listOf(CreateRequisitionItemRequest(itemName = "Kirvis"))))
        assertRoundTrip(RequisitionUnitReviewRequest(action = "FORWARDED", rejectionReason = null))
        assertRoundTrip(RequisitionTopLevelReviewRequest(action = "APPROVED", rejectionReason = null))
        assertRoundTrip(CreateReservationItemRequest(itemId = "item-1", quantity = 3))
        assertRoundTrip(
            CreateReservationRequest(
                title = "Zygio rezervacija",
                items = listOf(CreateReservationItemRequest(itemId = "item-2", quantity = 1)),
                itemId = "item-legacy",
                quantity = 2,
                startDate = "2026-08-10T09:00:00Z",
                endDate = "2026-08-12T18:00:00Z",
                requestingUnitId = "unit-4",
                eventId = "event-2",
                pickupLocationId = "loc-2",
                returnLocationId = "loc-3",
                notes = "C"
            )
        )
        assertRoundTrip(UpdateReservationStatusRequest(status = "CANCELLED", notes = "D"))
        assertRoundTrip(ReviewReservationRequest(status = "APPROVED", notes = "E"))
        assertRoundTrip(ReservationMovementItemRequest(itemId = "item-3", quantity = 4))
        assertRoundTrip(ReservationMovementRequest(items = listOf(ReservationMovementItemRequest(itemId = "item-4", quantity = 5)), locationId = "loc-4", notes = "F"))
        assertRoundTrip(UpdateReservationPickupRequest(pickupAt = "2026-08-10T10:00:00Z", pickupLocationId = "loc-5", response = "ACCEPTED"))
        assertRoundTrip(UpdateReservationReturnTimeRequest(returnAt = "2026-08-12T17:00:00Z", returnLocationId = "loc-6", response = "DECLINED"))
    }

    @Test
    fun `requisition and reservation response models round trip through json`() {
        val requisitionItem = RequisitionItemResponse(
            id = "req-item-1",
            itemId = "item-1",
            itemName = "Kirvis",
            itemDescription = "Plieninis",
            quantityRequested = 2,
            quantityApproved = 1,
            rejectionReason = null,
            notes = "A"
        )
        val reservationItem = ReservationItemResponse(
            itemId = "item-2",
            itemName = "Palapine",
            quantity = 3,
            custodianId = "unit-1",
            custodianName = "Skautai",
            remainingAfterReservation = 5
        )
        val reservation = ReservationResponse(
            id = "reservation-1",
            title = "Zygis",
            tuntasId = "tuntas-1",
            reservedByUserId = "user-1",
            reservedByName = "Rezervavo",
            approvedByUserId = "user-2",
            requestingUnitId = "unit-2",
            requestingUnitName = "Vilku draugove",
            eventId = "event-3",
            totalItems = 1,
            totalQuantity = 3,
            startDate = "2026-08-10T09:00:00Z",
            endDate = "2026-08-12T18:00:00Z",
            status = "APPROVED",
            unitReviewStatus = "APPROVED",
            unitReviewedByUserId = "user-3",
            unitReviewedAt = "2026-08-01T09:00:00Z",
            topLevelReviewStatus = "APPROVED",
            topLevelReviewedByUserId = "user-4",
            topLevelReviewedAt = "2026-08-01T10:00:00Z",
            pickupAt = "2026-08-10T08:00:00Z",
            pickupLocationId = "loc-7",
            pickupLocationPath = "Sandelis/1",
            pickupProposalStatus = "ACCEPTED",
            pickupProposedAt = "2026-08-02T09:00:00Z",
            pickupProposedByUserId = "user-5",
            pickupRespondedAt = "2026-08-02T10:00:00Z",
            pickupRespondedByUserId = "user-6",
            returnAt = "2026-08-12T17:00:00Z",
            returnLocationId = "loc-8",
            returnLocationPath = "Sandelis/2",
            returnProposalStatus = "ACCEPTED",
            returnProposedAt = "2026-08-03T09:00:00Z",
            returnProposedByUserId = "user-7",
            returnRespondedAt = "2026-08-03T10:00:00Z",
            returnRespondedByUserId = "user-8",
            notes = "B",
            createdAt = "2026-08-01T08:00:00Z",
            updatedAt = "2026-08-01T08:30:00Z",
            items = listOf(reservationItem)
        )

        assertRoundTrip(requisitionItem)
        assertRoundTrip(
            RequisitionResponse(
                id = "requisition-1",
                tuntasId = "tuntas-1",
                createdByUserId = "user-1",
                requestingUnitId = "unit-1",
                requestingUnitName = "Skautai",
                status = "FORWARDED",
                unitReviewStatus = "APPROVED",
                unitReviewedByUserId = "user-2",
                unitReviewedAt = "2026-08-01T09:00:00Z",
                topLevelReviewStatus = "PENDING",
                topLevelReviewedByUserId = null,
                topLevelReviewedAt = null,
                reviewLevel = "TOP_LEVEL",
                lastAction = "FORWARDED",
                neededByDate = "2026-08-15",
                notes = "C",
                items = listOf(requisitionItem),
                createdAt = "2026-08-01T07:00:00Z",
                updatedAt = "2026-08-01T07:30:00Z"
            )
        )
        assertRoundTrip(RequisitionListResponse(requests = emptyList(), total = 0))
        assertRoundTrip(reservationItem)
        assertRoundTrip(reservation)
        assertRoundTrip(ReservationListResponse(reservations = listOf(reservation), total = 1))
        assertRoundTrip(
            ReservationAvailabilityResponse(
                startDate = "2026-08-10T09:00:00Z",
                endDate = "2026-08-12T18:00:00Z",
                items = listOf(ReservationAvailabilityItemResponse(itemId = "item-3", totalQuantity = 10, reservedQuantity = 4, availableQuantity = 6))
            )
        )
        assertRoundTrip(
            ReservationMovementResponse(
                id = "movement-1",
                reservationId = "reservation-1",
                itemId = "item-4",
                itemName = "Virve",
                locationId = "loc-9",
                locationPath = "Sandelis/3",
                type = "ISSUE",
                quantity = 2,
                performedByUserId = "user-9",
                notes = "D",
                createdAt = "2026-08-01T11:00:00Z"
            )
        )
        assertRoundTrip(ReservationMovementListResponse(movements = emptyList(), total = 0))
    }
}
