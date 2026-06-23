package lt.skautai.plugins

import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lt.skautai.routes.authRoutes
import lt.skautai.routes.bendrasInventoryRequestRoutes
import lt.skautai.routes.deviceRoutes
import lt.skautai.routes.invitationRoutes
import lt.skautai.routes.inventoryTemplateRoutes
import lt.skautai.routes.inventoryKitRoutes
import lt.skautai.routes.itemRoutes
import lt.skautai.routes.superAdminRoutes
import lt.skautai.routes.locationRoutes
import lt.skautai.routes.leadershipChangeRequestRoutes
import lt.skautai.routes.liveEventRoutes
import lt.skautai.routes.memberRoutes
import lt.skautai.routes.mobileRoutes
import lt.skautai.routes.myTaskRoutes
import lt.skautai.routes.notificationRoutes
import lt.skautai.routes.organizationalUnitRoutes
import lt.skautai.routes.requisitionRoutes
import lt.skautai.services.AuthService
import lt.skautai.services.InvitationService
import lt.skautai.services.ItemCheckService
import lt.skautai.services.ItemService
import lt.skautai.services.InventoryTemplateService
import lt.skautai.services.InventoryKitService
import lt.skautai.services.LocationService
import lt.skautai.services.LeadershipChangeRequestService
import lt.skautai.services.OrganizationalUnitService
import lt.skautai.services.MemberService
import lt.skautai.services.MyTaskService
import lt.skautai.services.NotificationRecipientService
import lt.skautai.services.NotificationService
import lt.skautai.routes.reservationRoutes
import lt.skautai.services.ReservationService
import lt.skautai.routes.eventRoutes
import lt.skautai.routes.userRoutes
import lt.skautai.services.BendrasInventoryRequestService
import lt.skautai.services.DeviceService
import lt.skautai.services.EventService
import lt.skautai.services.EventPackingService
import lt.skautai.services.EventInventoryReminderService
import lt.skautai.services.FirebaseNotificationService
import lt.skautai.services.RequisitionService
import lt.skautai.routes.rolesRoutes
import lt.skautai.routes.uploadRoutes

fun Application.configureRouting() {
    val authService = AuthService(environment)
    val invitationService = InvitationService()
    val itemService = ItemService()
    val itemCheckService = ItemCheckService()
    val locationService = LocationService()
    val organizationalUnitService = OrganizationalUnitService()
    val memberService = MemberService()
    val reservationService = ReservationService()
    val eventService = EventService()
    val eventPackingService = EventPackingService()
    val bendrasInventoryRequestService = BendrasInventoryRequestService()
    val requisitionService = RequisitionService()
    val inventoryTemplateService = InventoryTemplateService()
    val inventoryKitService = InventoryKitService()
    val myTaskService = MyTaskService()
    val leadershipChangeRequestService = LeadershipChangeRequestService()
    val deviceService = DeviceService()
    val notificationService = NotificationService()
    val firebaseNotificationService = FirebaseNotificationService(deviceService, notificationService)
    val eventInventoryReminderService = EventInventoryReminderService(firebaseNotificationService)
    val notificationRecipientService = NotificationRecipientService()

    routing {
        authRoutes(authService)
        invitationRoutes(invitationService)
        superAdminRoutes(
            memberService,
            organizationalUnitService,
            firebaseNotificationService,
            notificationRecipientService
        )
        itemRoutes(itemService, itemCheckService)
        locationRoutes(locationService)
        organizationalUnitRoutes(organizationalUnitService)
        memberRoutes(memberService)
        leadershipChangeRequestRoutes(leadershipChangeRequestService)
        reservationRoutes(reservationService, firebaseNotificationService, notificationRecipientService)
        eventRoutes(eventService, memberService, eventPackingService, firebaseNotificationService)
        bendrasInventoryRequestRoutes(bendrasInventoryRequestService, firebaseNotificationService, notificationRecipientService)
        requisitionRoutes(requisitionService, firebaseNotificationService, notificationRecipientService)
        inventoryTemplateRoutes(inventoryTemplateService)
        inventoryKitRoutes(inventoryKitService)
        myTaskRoutes(myTaskService)
        notificationRoutes(notificationService)
        mobileRoutes(
            itemService,
            reservationService,
            bendrasInventoryRequestService,
            requisitionService,
            eventService,
            organizationalUnitService,
            myTaskService
        )
        userRoutes()
        rolesRoutes()
        uploadRoutes()
        liveEventRoutes()
        deviceRoutes(deviceService, firebaseNotificationService)
    }

    val reminderJob = launch {
        while (isActive) {
            runCatching { eventInventoryReminderService.dispatchDueReminders() }
                .onFailure { log.error("Failed to dispatch event inventory reminders", it) }
            delay(60 * 60 * 1000L)
        }
    }
    monitor.subscribe(ApplicationStopped) {
        reminderJob.cancel()
    }
}
