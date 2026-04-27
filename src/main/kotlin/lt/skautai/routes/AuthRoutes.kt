package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.LoginRequest
import lt.skautai.models.requests.RefreshTokenRequest
import lt.skautai.models.requests.RegisterTuntininkasRequest
import lt.skautai.models.requests.RegisterWithInviteRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.services.AuthService

fun Route.authRoutes(authService: AuthService) {
    route("/api/auth") {
        post("/register") {
            val request = call.receive<RegisterTuntininkasRequest>()
            authService.registerTuntininkas(request)
                .onSuccess { call.respond(HttpStatusCode.Created, it) }
                .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Registration failed")) }
        }

        post("/register/invite") {
            val request = call.receive<RegisterWithInviteRequest>()
            authService.registerWithInvite(request)
                .onSuccess { call.respond(HttpStatusCode.Created, it) }
                .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Registration failed")) }
        }

        post("/login") {
            val request = call.receive<LoginRequest>()
            authService.login(request)
                .onSuccess { call.respond(HttpStatusCode.OK, it) }
                .onFailure { call.respond(HttpStatusCode.Unauthorized, ErrorResponse(it.message ?: "Login failed")) }
        }

        post("/refresh") {
            val request = call.receive<RefreshTokenRequest>()
            authService.refreshAccessToken(request.refreshToken)
                .onSuccess { call.respond(HttpStatusCode.OK, it) }
                .onFailure { call.respond(HttpStatusCode.Unauthorized, ErrorResponse(it.message ?: "Refresh failed")) }
        }
    }
    route("/api/setup") {
        post("/super-admin") {
            val bootstrapToken = application.environment.config
                .propertyOrNull("setup.bootstrapToken")?.getString().orEmpty()
            if (bootstrapToken.isBlank()) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            val provided = call.request.headers["X-Bootstrap-Token"].orEmpty()
            if (provided != bootstrapToken) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid bootstrap token"))
                return@post
            }
            val request = call.receive<LoginRequest>()
            authService.seedSuperAdmin(request)
                .onSuccess { call.respond(HttpStatusCode.Created, it) }
                .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Setup failed")) }
        }
    }
    route("/api/super-admin") {
        post("/login") {
            val request = call.receive<LoginRequest>()
            authService.loginSuperAdmin(request)
                .onSuccess { call.respond(HttpStatusCode.OK, it) }
                .onFailure { call.respond(HttpStatusCode.Unauthorized, ErrorResponse(it.message ?: "Login failed")) }
        }
    }
}
