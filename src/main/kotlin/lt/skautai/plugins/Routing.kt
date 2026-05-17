package lt.skautai.plugins

import io.ktor.server.application.*
import io.ktor.server.routing.*
import lt.skautai.routes.authRoutes
import lt.skautai.routes.bendrasInventoryRequestRoutes
import lt.skautai.routes.invitationRoutes
import lt.skautai.routes.inventoryTemplateRoutes
import lt.skautai.routes.itemRoutes
import lt.skautai.routes.superAdminRoutes
import lt.skautai.routes.locationRoutes
import lt.skautai.routes.memberRoutes
import lt.skautai.routes.organizationalUnitRoutes
import lt.skautai.routes.requisitionRoutes
import lt.skautai.services.AuthService
import lt.skautai.services.InvitationService
import lt.skautai.services.ItemCheckService
import lt.skautai.services.ItemService
import lt.skautai.services.InventoryTemplateService
import lt.skautai.services.LocationService
import lt.skautai.services.OrganizationalUnitService
import lt.skautai.services.MemberService
import lt.skautai.routes.reservationRoutes
import lt.skautai.services.ReservationService
import lt.skautai.routes.eventRoutes
import lt.skautai.routes.userRoutes
import lt.skautai.services.BendrasInventoryRequestService
import lt.skautai.services.EventService
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
    val bendrasInventoryRequestService = BendrasInventoryRequestService()
    val requisitionService = RequisitionService()
    val inventoryTemplateService = InventoryTemplateService()

    routing {
        authRoutes(authService)
        invitationRoutes(invitationService)
        superAdminRoutes(memberService, organizationalUnitService)
        itemRoutes(itemService, itemCheckService)
        locationRoutes(locationService)
        organizationalUnitRoutes(organizationalUnitService)
        memberRoutes(memberService)
        reservationRoutes(reservationService)
        eventRoutes(eventService, memberService)
        bendrasInventoryRequestRoutes(bendrasInventoryRequestService)
        requisitionRoutes(requisitionService)
        inventoryTemplateRoutes(inventoryTemplateService)
        userRoutes()
        rolesRoutes()
        uploadRoutes()
    }
}
