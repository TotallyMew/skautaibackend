package lt.skautai.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class MobileHomeSummaryResponse(
    val activeUnitId: String? = null,
    val activeUnitName: String? = null,
    val availableUnits: List<OrganizationalUnitResponse> = emptyList(),
    val activeUnitItemCount: Int = 0,
    val activeUnitFromSharedCount: Int = 0,
    val sharedInventoryCount: Int = 0,
    val sharedPendingApprovalCount: Int = 0,
    val personalLendingCount: Int = 0,
    val requisitionCount: Int = 0,
    val myRequisitionCount: Int = 0,
    val assignedRequisitionCount: Int = 0,
    val sharedRequestCount: Int = 0,
    val myReservationCount: Int = 0,
    val assignedReservationCount: Int = 0,
    val trackedReservationCount: Int = 0,
    val activeReservations: List<ReservationResponse> = emptyList(),
    val tasks: List<MyTaskResponse> = emptyList(),
    val taskTotalCount: Int = 0
)

@Serializable
data class MobileCacheStateResourceResponse(
    val resource: String,
    val maxUpdatedAt: String? = null,
    val total: Int,
    val versionKey: String
)

@Serializable
data class MobileCacheStateResponse(
    val resources: List<MobileCacheStateResourceResponse>
)
