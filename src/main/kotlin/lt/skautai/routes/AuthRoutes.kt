package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.LoginRequest
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

    }
    route("/api/setup") {
        post("/super-admin") {
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