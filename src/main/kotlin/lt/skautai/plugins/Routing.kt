package lt.skautai.plugins

import io.ktor.server.application.*
import io.ktor.server.routing.*
import lt.skautai.routes.authRoutes
import lt.skautai.routes.invitationRoutes
import lt.skautai.routes.superAdminRoutes
import lt.skautai.services.AuthService
import lt.skautai.services.InvitationService

fun Application.configureRouting() {
    val authService = AuthService(environment)
    val invitationService = InvitationService()

    routing {
        authRoutes(authService)
        invitationRoutes(invitationService)
        superAdminRoutes()
    }
}