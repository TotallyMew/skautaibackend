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

    private inline fun <reified T> assertDecodesTo(rawJson: String, expected: T) {
        assertEquals(expected, json.decodeFromString<T>(rawJson))
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
            isReturned = false,
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

    @Test
    fun `inventory membership and support models round trip through json`() {
        assertRoundTrip(CreateInvitationRequest(roleId = "role-1", organizationalUnitId = "unit-1", expiresInHours = 24, expiresAt = "2026-08-01T12:00:00Z"))
        assertRoundTrip(AcceptInvitationRequest(code = "INV-CODE-1"))
        assertRoundTrip(AssignUnitMemberRequest(userId = "user-1", assignmentType = "VADOVO_PADEJEJAS"))
        assertRoundTrip(UpdateMyProfileRequest(name = "Vardenis", surname = "Pavardenis", email = "user@test.com", phone = "+37060000000"))
        assertRoundTrip(ChangeMyPasswordRequest(currentPassword = "old-pass", newPassword = "new-pass"))
        assertRoundTrip(CreateOrganizationalUnitRequest(name = "Skautu draugove", type = "DRAUGOVE", subType = "SKAUTU", acceptedRankId = "rank-1"))
        assertRoundTrip(UpdateOrganizationalUnitRequest(name = "Atnaujinta draugove", acceptedRankId = "rank-2"))
        assertRoundTrip(CreateLocationRequest(name = "Sandelis", visibility = "UNIT", parentLocationId = "loc-parent", ownerUnitId = "unit-2", address = "Vilnius", description = "A", latitude = 54.7, longitude = 25.3))
        assertRoundTrip(UpdateLocationRequest(name = "Naujas sandelis", visibility = "PUBLIC", parentLocationId = "loc-parent-2", ownerUnitId = "unit-3", address = "Kaunas", description = "B", latitude = 55.0, longitude = 24.0))
        assertRoundTrip(ItemCustomFieldRequest(fieldName = "Serijos numeris", fieldValue = "SN-001"))
        assertRoundTrip(
            CreateItemRequest(
                name = "Palapine",
                description = "Dviviete",
                type = "COLLECTIVE",
                category = "CAMPING",
                custodianId = "unit-4",
                origin = "UNIT_ACQUIRED",
                quantity = 2,
                condition = "GOOD",
                locationId = "loc-10",
                temporaryStorageLabel = "Lentyna 1",
                sourceSharedItemId = "shared-1",
                responsibleUserId = "user-2",
                photoUrl = "/files/palapine.jpg",
                purchaseDate = "2026-06-01",
                purchasePrice = 199.99,
                notes = "C",
                customFields = listOf(ItemCustomFieldRequest(fieldName = "Dydis", fieldValue = "XL")),
                duplicateHandling = "MERGE",
                duplicateTargetItemId = "item-merge-1"
            )
        )
        assertRoundTrip(
            UpdateItemRequest(
                name = "Palapine XL",
                description = "Triviete",
                type = "COLLECTIVE",
                category = "CAMPING",
                condition = "DAMAGED",
                quantity = 3,
                custodianId = "unit-5",
                locationId = "loc-11",
                temporaryStorageLabel = "Lentyna 2",
                sourceSharedItemId = "shared-2",
                responsibleUserId = "user-3",
                photoUrl = "/files/palapine-2.jpg",
                purchaseDate = "2026-06-02",
                purchasePrice = 149.5,
                notes = "D",
                customFields = listOf(ItemCustomFieldRequest(fieldName = "Spalva", fieldValue = "Zalia")),
                status = "INACTIVE",
                clearCustodianId = true,
                clearLocationId = true,
                clearSourceSharedItemId = true,
                clearResponsibleUserId = true
            )
        )
        assertRoundTrip(TransferItemToUnitRequest(targetUnitId = "unit-6", quantity = 4, notes = "Perdavimas"))
        assertRoundTrip(ReturnItemToSharedRequest(quantity = 2, notes = "Grazinimas"))
        assertRoundTrip(RestockItemRequest(quantity = 5, purchaseDate = "2026-06-03", purchasePrice = 89.0, notes = "Papildymas"))
        assertRoundTrip(ReviewItemAdditionRequest(decision = "REJECTED", rejectionReason = "Dublikatas"))
        assertRoundTrip(CreateBendrasInventoryRequestItemRequest(itemId = "item-10", quantity = 2))
        assertRoundTrip(
            CreateBendrasInventoryRequestRequest(
                itemId = "item-11",
                itemDescription = "Puodas",
                quantity = 3,
                neededByDate = "2026-08-20",
                startDate = "2026-08-18",
                endDate = "2026-08-22",
                requestingUnitId = "unit-7",
                needsDraugininkasApproval = true,
                notes = "E",
                items = listOf(CreateBendrasInventoryRequestItemRequest(itemId = "item-12", quantity = 1))
            )
        )
        assertRoundTrip(DraugininkasReviewRequest(action = "REJECTED", rejectionReason = "Netinka"))
        assertRoundTrip(TopLevelReviewRequest(action = "APPROVED", rejectionReason = null))
        assertRoundTrip(RequisitionMarkPurchasedRequest(notes = "Nupirkta"))
        assertRoundTrip(
            AddRequisitionItemToInventoryRequest(
                requisitionItemId = "req-item-10",
                action = "CREATE_NEW",
                existingItemId = "item-existing",
                custodianId = "unit-8",
                type = "COLLECTIVE",
                category = "TOOLS",
                condition = "GOOD",
                purchaseDate = "2026-08-10",
                purchasePrice = 77.7,
                notes = "F"
            )
        )
        assertRoundTrip(AddRequisitionToInventoryRequest(items = listOf(AddRequisitionItemToInventoryRequest(requisitionItemId = "req-item-11", action = "RESTOCK_EXISTING"))))
        assertRoundTrip(CreateInventoryTemplateRequest(name = "Stovyklos sablonas", eventType = "STOVYKLA", items = listOf(InventoryTemplateItemRequest(itemId = "item-13", itemName = "Kirvis", quantity = 2, category = "TOOLS", notes = "G"))))
        assertRoundTrip(UpdateInventoryTemplateRequest(name = "Atnaujintas sablonas", eventType = "ZYGIS", items = listOf(InventoryTemplateItemRequest(itemName = "Virve", quantity = 4))))
        assertRoundTrip(ApplyInventoryTemplateRequest(templateId = "template-1"))
        assertRoundTrip(AssignLeadershipRoleRequest(roleId = "role-2", organizationalUnitId = "unit-9", startsAt = "2026-01-01", expiresAt = "2026-12-31", termNumber = 2))
        assertRoundTrip(UpdateLeadershipRoleRequest(startsAt = "2026-02-01", expiresAt = "2026-11-30", termStatus = "ACTIVE", organizationalUnitId = "unit-10"))
        assertRoundTrip(TransferTuntininkasRequest(successorUserId = "user-4"))
        assertRoundTrip(AssignRankRequest(roleId = "rank-role-1"))

        val unitMembership = UnitMembershipResponse(
            id = "membership-1",
            userId = "user-5",
            userName = "Jonas",
            userSurname = "Jonaitis",
            organizationalUnitId = "unit-11",
            organizationalUnitName = "Vilku draugove",
            tuntasId = "tuntas-2",
            assignmentType = "MEMBER",
            assignedByUserId = "user-6",
            joinedAt = "2026-01-10T10:00:00Z",
            leftAt = null
        )
        val location = LocationResponse(
            id = "loc-12",
            tuntasId = "tuntas-3",
            name = "Pagrindinis sandelis",
            visibility = "PUBLIC",
            parentLocationId = null,
            ownerUserId = "user-7",
            ownerUnitId = "unit-12",
            ownerUnitName = "Skautai",
            fullPath = "Sandelis/Pagrindinis",
            hasChildren = true,
            isLeafSelectable = false,
            isEditable = true,
            address = "Gedimino pr. 1",
            description = "H",
            latitude = 54.6872,
            longitude = 25.2797,
            createdAt = "2026-02-01T10:00:00Z"
        )
        val templateItem = InventoryTemplateItemResponse(
            id = "template-item-1",
            templateId = "template-2",
            itemId = "item-14",
            itemName = "Puodas",
            quantity = 3,
            category = "COOKING",
            notes = "I"
        )
        val template = InventoryTemplateResponse(
            id = "template-2",
            tuntasId = "tuntas-4",
            name = "Vasaros stovykla",
            eventType = "STOVYKLA",
            createdByUserId = "user-8",
            createdByUserName = "Kurejas",
            createdAt = "2026-03-01T08:00:00Z",
            items = listOf(templateItem)
        )
        val bendrasRequest = BendrasInventoryRequestResponse(
            id = "bendras-1",
            tuntasId = "tuntas-5",
            requestedByUserId = "user-9",
            requestedByUserName = "Prasytojas",
            itemId = "item-15",
            itemName = "Kirvis",
            itemDescription = "Mazasis",
            quantity = 2,
            neededByDate = "2026-09-01",
            eventId = "event-1",
            requestingUnitId = "unit-13",
            requestingUnitName = "Patyre skautai",
            needsDraugininkasApproval = true,
            draugininkasStatus = "APPROVED",
            draugininkasReviewedByUserId = "user-10",
            draugininkasRejectionReason = null,
            topLevelStatus = "PENDING",
            topLevelReviewedByUserId = null,
            topLevelRejectionReason = null,
            notes = "J",
            items = listOf(BendrasInventoryRequestItemResponse(id = "bendras-item-1", itemId = "item-15", itemName = "Kirvis", quantity = 2)),
            createdAt = "2026-03-01T09:00:00Z",
            updatedAt = "2026-03-01T09:30:00Z"
        )

        assertRoundTrip(TokenResponse(token = "token-1", refreshToken = "refresh-1", userId = "user-11", email = "token@test.com", name = "Token User", type = "user", tuntai = listOf(TuntasInfo(id = "tuntas-6", name = "Vilniaus tuntas", krastas = "Vilniaus", contactEmail = "vilnius@test.com", status = "ACTIVE"))))
        assertRoundTrip(MessageResponse(message = "OK"))
        assertRoundTrip(ErrorResponse(error = "Tvarkinga klaida"))
        assertRoundTrip(MyProfileResponse(userId = "user-12", name = "Petras", surname = "Petraitis", email = "profile@test.com", phone = "+37061111111", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-02T00:00:00Z"))
        assertRoundTrip(InvitationResponse(code = "INV-2", tuntasId = "tuntas-7", roleName = "skautas", tuntasName = "Kauno tuntas", expiresAt = "2026-04-01T00:00:00Z", organizationalUnitId = "unit-14", organizationalUnitName = "Skautu draugove"))
        assertRoundTrip(RoleResponse(id = "role-3", name = "inventorininkas", roleType = "SYSTEM", isSystemRole = true))
        assertRoundTrip(RoleListResponse(roles = listOf(RoleResponse(id = "role-4", name = "draugininkas", roleType = "LEADERSHIP", isSystemRole = false)), total = 1))
        assertRoundTrip(TuntasInfo(id = "tuntas-8", name = "Klaipedos tuntas", krastas = "Klaipedos", contactEmail = "klaipeda@test.com", status = "PENDING"))
        assertRoundTrip(unitMembership)
        assertRoundTrip(UnitMembershipListResponse(members = listOf(unitMembership), total = 1))
        assertRoundTrip(OrganizationalUnitResponse(id = "unit-15", tuntasId = "tuntas-9", name = "Gildija", type = "GILDIJA", subType = "VADOVU", acceptedRankId = "rank-3", acceptedRankName = "Skiltininkas", memberCount = 5, itemCount = 8, createdAt = "2026-05-01T10:00:00Z"))
        assertRoundTrip(OrganizationalUnitListResponse(units = emptyList(), total = 0))
        assertRoundTrip(location)
        assertRoundTrip(LocationListResponse(locations = listOf(location), total = 1))
        assertRoundTrip(UploadResponse(url = "/files/doc-1.pdf"))
        assertRoundTrip(templateItem)
        assertRoundTrip(template)
        assertRoundTrip(InventoryTemplateListResponse(templates = listOf(template), total = 1))
        assertRoundTrip(AppliedTemplateReservedItemResponse(templateItemName = "Puodas", itemId = "item-16", itemName = "Didelis puodas", eventInventoryItemId = "event-item-10", reservationGroupId = "reservation-group-1", quantity = 2))
        assertRoundTrip(AppliedTemplatePurchaseItemResponse(templateItemName = "Kirvis", eventInventoryItemId = "event-item-11", purchaseId = "purchase-1", purchaseItemId = "purchase-item-1", quantity = 1))
        assertRoundTrip(
            AppliedInventoryTemplateResponse(
                reserved = listOf(AppliedTemplateReservedItemResponse(templateItemName = "Virve", itemId = "item-17", itemName = "Alpinistine virve", eventInventoryItemId = "event-item-12", reservationGroupId = "reservation-group-2", quantity = 4)),
                toPurchase = listOf(AppliedTemplatePurchaseItemResponse(templateItemName = "Dujos", eventInventoryItemId = "event-item-13", purchaseId = "purchase-2", purchaseItemId = "purchase-item-2", quantity = 2)),
                reservedTotal = 4,
                toPurchaseTotal = 2
            )
        )
        assertRoundTrip(BendrasInventoryRequestItemResponse(id = "bendras-item-2", itemId = "item-18", itemName = "Lempa", quantity = 1))
        assertRoundTrip(bendrasRequest)
        assertRoundTrip(BendrasInventoryRequestListResponse(requests = listOf(bendrasRequest), total = 1))
    }

    @Test
    fun `remaining high value request and response models round trip through json`() {
        assertRoundTrip(CreateEventInventoryRequestRequest(eventInventoryItemId = "event-item-200", quantity = 5, notes = "Need more"))
        assertRoundTrip(AddPastovykleMemberRequest(userId = "user-200"))
        assertRoundTrip(AssignPastovykleLeaderRequest(userId = "user-201"))

        val itemResponse = ItemResponse(
            id = "item-200",
            qrToken = "qr-token-200",
            tuntasId = "tuntas-200",
            custodianId = "unit-200",
            custodianName = "Skautu draugove",
            origin = "UNIT_ACQUIRED",
            name = "Palapine",
            description = "Keturiu vietu",
            type = "COLLECTIVE",
            category = "CAMPING",
            condition = "GOOD",
            quantity = 6,
            locationId = "loc-200",
            locationName = "Pagrindinis sandelis",
            locationPath = "Sandelis/Aukstas1",
            temporaryStorageLabel = "Lentyna A",
            sourceSharedItemId = "shared-200",
            responsibleUserId = "user-202",
            responsibleUserName = "Atsakingas Vadovas",
            createdByUserId = "user-203",
            createdByUserName = "Kurejas",
            photoUrl = "/files/item-200.jpg",
            purchaseDate = "2026-04-01",
            purchasePrice = 249.99,
            notes = "Pilnas komplektas",
            customFields = listOf(
                ItemCustomFieldResponse(id = "field-1", fieldName = "Spalva", fieldValue = "Zalia"),
                ItemCustomFieldResponse(id = "field-2", fieldName = "Gamintojas", fieldValue = "ScoutCo")
            ),
            quantityBreakdown = listOf(
                ItemDistributionResponse(holderName = "Skautu draugove", quantity = 4),
                ItemDistributionResponse(holderName = "Bendras sandelis", quantity = 2)
            ),
            totalQuantityAcrossCustodians = 6,
            status = "ACTIVE",
            submittedByUserId = "user-204",
            submittedByUserName = "Teikejas",
            targetScope = "SHARED",
            reviewedByUserId = "user-205",
            rejectionReason = null,
            createdAt = "2026-04-01T10:00:00Z",
            updatedAt = "2026-04-02T10:00:00Z"
        )
        val itemAssignment = ItemAssignmentResponse(
            id = "assignment-1",
            itemId = "item-200",
            assignedToUserId = "user-206",
            assignedToUserName = "Gavejas",
            assignedByUserId = "user-203",
            assignedByUserName = "Kurejas",
            assignedAt = "2026-04-03T10:00:00Z",
            unassignedAt = null,
            reason = "Renginiui",
            notes = "Atsargiai naudoti"
        )
        val itemConditionLog = ItemConditionLogResponse(
            id = "condition-1",
            itemId = "item-200",
            previousCondition = "GOOD",
            newCondition = "WORN",
            reportedByUserId = "user-207",
            reportedByUserName = "Tikrinantis",
            reportedAt = "2026-04-04T10:00:00Z",
            notes = "Nedidelis nusidevejimas"
        )
        val itemTransfer = ItemTransferResponse(
            id = "transfer-1",
            itemId = "item-200",
            fromCustodianId = null,
            fromCustodianName = "Bendras sandelis",
            toCustodianId = "unit-200",
            toCustodianName = "Skautu draugove",
            initiatedByUserId = "user-208",
            initiatedByUserName = "Inventorininkas",
            approvedByUserId = "user-209",
            approvedByUserName = "Tuntininkas",
            notes = "Perduota stovyklai",
            status = "COMPLETED",
            createdAt = "2026-04-05T10:00:00Z",
            completedAt = "2026-04-05T11:00:00Z"
        )
        val itemHistory = ItemHistoryResponse(
            id = "history-1",
            itemId = "item-200",
            eventType = "TRANSFERRED",
            quantityChange = -2,
            performedByUserId = "user-208",
            performedByUserName = "Inventorininkas",
            requisitionId = "req-200",
            notes = "Perkelta i vieneta",
            createdAt = "2026-04-05T10:30:00Z"
        )
        val candidate = EventPurchaseReconciliationCandidateResponse(
            itemId = "item-201",
            name = "Kirvis",
            quantity = 3,
            custodianId = "unit-201",
            custodianName = "Vilku draugove",
            recommended = true
        )
        val pastovykleMember = PastovykleMemberResponse(
            id = "past-member-1",
            pastovykleId = "past-1",
            userId = "user-210",
            userName = "Stovyklininkas",
            status = "ACTIVE",
            addedAt = "2026-04-06T09:00:00Z",
            addedByUserId = "user-211"
        )
        val memberLeadershipRole = MemberLeadershipRoleResponse(
            id = "lead-role-1",
            roleId = "role-200",
            roleName = "draugininkas",
            organizationalUnitId = "unit-202",
            organizationalUnitName = "Patyrusiu skautu draugove",
            assignedByUserId = "user-212",
            assignedAt = "2026-01-01T00:00:00Z",
            startsAt = "2026-01-01",
            expiresAt = "2026-12-31",
            leftAt = null,
            termNumber = 1,
            termStatus = "ACTIVE"
        )
        val memberRank = MemberRankResponse(
            id = "rank-200",
            roleId = "rank-role-200",
            roleName = "skiltininkas",
            assignedByUserId = "user-212",
            assignedAt = "2026-02-01T00:00:00Z"
        )
        val memberUnitAssignment = MemberUnitAssignmentResponse(
            id = "member-unit-1",
            organizationalUnitId = "unit-202",
            organizationalUnitName = "Patyrusiu skautu draugove",
            assignmentType = "MEMBER",
            joinedAt = "2025-09-01T00:00:00Z"
        )
        val memberResponse = MemberResponse(
            userId = "user-213",
            name = "Vardenis",
            surname = "Pavardenis",
            email = "member@test.com",
            phone = "+37061234567",
            joinedAt = "2025-09-01T00:00:00Z",
            unitAssignments = listOf(memberUnitAssignment),
            leadershipRoles = listOf(memberLeadershipRole),
            leadershipRoleHistory = listOf(memberLeadershipRole.copy(id = "lead-role-2", termStatus = "FINISHED")),
            ranks = listOf(memberRank)
        )

        assertRoundTrip(itemResponse)
        assertRoundTrip(ItemListResponse(items = listOf(itemResponse), total = 1))
        assertRoundTrip(itemAssignment)
        assertRoundTrip(ItemAssignmentListResponse(assignments = listOf(itemAssignment), total = 1))
        assertRoundTrip(itemConditionLog)
        assertRoundTrip(ItemConditionLogListResponse(entries = listOf(itemConditionLog), total = 1))
        assertRoundTrip(itemTransfer)
        assertRoundTrip(ItemTransferListResponse(transfers = listOf(itemTransfer), total = 1))
        assertRoundTrip(itemHistory)
        assertRoundTrip(ItemHistoryListResponse(entries = listOf(itemHistory), total = 1))
        assertRoundTrip(ItemQrResolveResponse(itemId = "item-200"))
        assertRoundTrip(DuplicateItemConflictResponse(error = "Duplicate item found", duplicateItem = itemResponse))

        assertRoundTrip(candidate)
        assertRoundTrip(EventPurchaseReconciliationCandidateListResponse(candidates = listOf(candidate), total = 1))
        assertRoundTrip(pastovykleMember)
        assertRoundTrip(PastovykleMemberListResponse(members = listOf(pastovykleMember), total = 1))
        assertRoundTrip(memberLeadershipRole)
        assertRoundTrip(memberRank)
        assertRoundTrip(memberUnitAssignment)
        assertRoundTrip(memberResponse)
        assertRoundTrip(MemberListResponse(members = listOf(memberResponse), total = 1))
    }

    @Test
    fun `request models decode defaults and explicit null variants`() {
        assertDecodesTo(
            """{"requisitionItemId":"req-1","action":"RESTOCK_EXISTING"}""",
            AddRequisitionItemToInventoryRequest(
                requisitionItemId = "req-1",
                action = "RESTOCK_EXISTING"
            )
        )
        assertDecodesTo(
            """{"requisitionItemId":"req-2","action":"CREATE_NEW","existingItemId":null,"custodianId":null,"purchaseDate":null,"purchasePrice":null,"notes":null}""",
            AddRequisitionItemToInventoryRequest(
                requisitionItemId = "req-2",
                action = "CREATE_NEW"
            )
        )

        assertDecodesTo(
            """{"quantity":3}""",
            RestockItemRequest(quantity = 3)
        )
        assertDecodesTo(
            """{"quantity":4,"purchaseDate":null,"purchasePrice":null,"notes":null}""",
            RestockItemRequest(quantity = 4)
        )

        assertDecodesTo(
            """{"name":"Nauja vieta"}""",
            CreateLocationRequest(name = "Nauja vieta")
        )
        assertDecodesTo(
            """{"name":"Null vieta","parentLocationId":null,"ownerUnitId":null,"address":null,"description":null,"latitude":null,"longitude":null}""",
            CreateLocationRequest(name = "Null vieta")
        )
        assertDecodesTo(
            """{}""",
            UpdateLocationRequest()
        )
        assertDecodesTo(
            """{"name":null,"visibility":null,"parentLocationId":null,"ownerUnitId":null,"address":null,"description":null,"latitude":null,"longitude":null}""",
            UpdateLocationRequest()
        )

        assertDecodesTo(
            """{"title":"Trumpa rezervacija","startDate":"2026-08-10T09:00:00Z","endDate":"2026-08-10T18:00:00Z"}""",
            CreateReservationRequest(
                title = "Trumpa rezervacija",
                startDate = "2026-08-10T09:00:00Z",
                endDate = "2026-08-10T18:00:00Z"
            )
        )
        assertDecodesTo(
            """{"startDate":"2026-08-11T09:00:00Z","endDate":"2026-08-11T18:00:00Z","itemId":null,"requestingUnitId":null,"eventId":null,"pickupLocationId":null,"returnLocationId":null,"notes":null}""",
            CreateReservationRequest(
                startDate = "2026-08-11T09:00:00Z",
                endDate = "2026-08-11T18:00:00Z"
            )
        )

        assertDecodesTo(
            """{"quantity":2,"notes":null}""",
            FulfillPastovykleInventoryRequestRequest(quantity = 2)
        )
        assertDecodesTo(
            """{"quantity":null,"notes":null}""",
            FulfillPastovykleInventoryRequestRequest()
        )

        assertDecodesTo(
            """{"quantity":null,"notes":null}""",
            UpdateEventInventoryAllocationRequest()
        )
        assertDecodesTo(
            """{"name":null,"plannedQuantity":null,"bucketId":null,"responsibleUserId":null,"notes":null}""",
            UpdateEventInventoryItemRequest()
        )
        assertDecodesTo(
            """{"status":null,"purchaseDate":null,"totalAmount":null,"invoiceFileUrl":null,"notes":null}""",
            UpdateEventPurchaseRequest()
        )
        assertDecodesTo(
            """{"eventInventoryItemId":"event-item-1","purchasedQuantity":1,"unitPrice":null,"notes":null}""",
            CreateEventPurchaseItemRequest(
                eventInventoryItemId = "event-item-1",
                purchasedQuantity = 1
            )
        )
        assertDecodesTo(
            """{"items":[{"eventInventoryItemId":"event-item-2","purchasedQuantity":2}],"purchaseDate":null,"notes":null}""",
            CreateEventPurchaseRequest(
                items = listOf(
                    CreateEventPurchaseItemRequest(
                        eventInventoryItemId = "event-item-2",
                        purchasedQuantity = 2
                    )
                )
            )
        )
        assertDecodesTo(
            """{"purchaseItemId":"purchase-item-1","decision":"CREATE_NEW","quantity":3,"existingItemId":null,"name":null,"notes":null}""",
            ReconcileEventPurchaseLineRequest(
                purchaseItemId = "purchase-item-1",
                decision = "CREATE_NEW",
                quantity = 3
            )
        )
        assertDecodesTo(
            """{"name":null,"startDate":null,"endDate":null,"locationId":null,"organizationalUnitId":null,"notes":null,"status":null}""",
            UpdateEventRequest()
        )

        assertDecodesTo(
            """{"name":"Stovyklos sablonas"}""",
            CreateInventoryTemplateRequest(name = "Stovyklos sablonas")
        )
        assertDecodesTo(
            """{"name":null,"eventType":null,"items":null}""",
            UpdateInventoryTemplateRequest()
        )
        assertDecodesTo(
            """{"itemName":"Virve"}""",
            InventoryTemplateItemRequest(itemName = "Virve")
        )

        assertDecodesTo(
            """{"name":"Kirvis","type":"COLLECTIVE","category":"TOOLS"}""",
            CreateItemRequest(
                name = "Kirvis",
                type = "COLLECTIVE",
                category = "TOOLS"
            )
        )
        assertDecodesTo(
            """{"name":"Kirvis","type":"COLLECTIVE","category":"TOOLS","description":null,"custodianId":null,"locationId":null,"temporaryStorageLabel":null,"sourceSharedItemId":null,"responsibleUserId":null,"photoUrl":null,"purchaseDate":null,"purchasePrice":null,"notes":null,"duplicateTargetItemId":null}""",
            CreateItemRequest(
                name = "Kirvis",
                type = "COLLECTIVE",
                category = "TOOLS"
            )
        )
        assertDecodesTo(
            """{}""",
            UpdateItemRequest()
        )
        assertDecodesTo(
            """{"name":null,"description":null,"type":null,"category":null,"condition":null,"quantity":null,"custodianId":null,"locationId":null,"temporaryStorageLabel":null,"sourceSharedItemId":null,"responsibleUserId":null,"photoUrl":null,"purchaseDate":null,"purchasePrice":null,"notes":null,"customFields":null,"status":null,"clearCustodianId":false,"clearLocationId":false,"clearSourceSharedItemId":false,"clearResponsibleUserId":false}""",
            UpdateItemRequest()
        )

        assertDecodesTo(
            """{"itemId":"item-20"}""",
            CreateBendrasInventoryRequestItemRequest(itemId = "item-20")
        )
        assertDecodesTo(
            """{"quantity":2,"neededByDate":null,"startDate":null,"endDate":null,"requestingUnitId":null,"needsDraugininkasApproval":null,"notes":null,"items":[]}""",
            CreateBendrasInventoryRequestRequest(quantity = 2)
        )
        assertDecodesTo(
            """{"itemId":null,"itemDescription":null,"quantity":1,"neededByDate":null,"startDate":null,"endDate":null,"requestingUnitId":null,"needsDraugininkasApproval":null,"notes":null,"items":[{"itemId":"item-21"}]}""",
            CreateBendrasInventoryRequestRequest(
                items = listOf(CreateBendrasInventoryRequestItemRequest(itemId = "item-21"))
            )
        )
    }
}
