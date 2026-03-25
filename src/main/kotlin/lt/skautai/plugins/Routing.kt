package lt.skautai.plugins

import io.ktor.server.application.*
import io.ktor.server.routing.*
import lt.skautai.routes.authRoutes
import lt.skautai.routes.invitationRoutes
import lt.skautai.routes.itemRoutes
import lt.skautai.routes.superAdminRoutes
import lt.skautai.routes.locationRoutes
import lt.skautai.routes.organizationalUnitRoutes
import lt.skautai.services.AuthService
import lt.skautai.services.InvitationService
import lt.skautai.services.ItemService
import lt.skautai.services.LocationService
import lt.skautai.services.OrganizationalUnitService


fun Application.configureRouting() {
    val authService = AuthService(environment)
    val invitationService = InvitationService()
    val itemService = ItemService()
    val locationService = LocationService()
    val organizationalUnitService = OrganizationalUnitService()

    routing {
        authRoutes(authService)
        invitationRoutes(invitationService)
        superAdminRoutes()
        itemRoutes(itemService)
        locationRoutes(locationService)
        organizationalUnitRoutes(organizationalUnitService)
    }
}