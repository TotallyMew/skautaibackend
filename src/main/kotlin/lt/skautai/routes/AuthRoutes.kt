package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.LoginRequest
import lt.skautai.models.requests.ForgotPasswordRequest
import lt.skautai.models.requests.ResetPasswordRequest
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

        post("/logout") {
            val request = call.receive<RefreshTokenRequest>()
            authService.logout(request.refreshToken)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/forgot-password") {
            val request = call.receive<ForgotPasswordRequest>()
            authService.requestPasswordReset(request)
                .onSuccess {
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Jei paskyra su šiuo el. paštu egzistuoja, išsiuntėme slaptažodžio atkūrimo nuorodą.")
                    )
                }
                .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Request failed")) }
        }

        post("/reset-password") {
            val request = call.receive<ResetPasswordRequest>()
            authService.resetPassword(request)
                .onSuccess { call.respond(HttpStatusCode.OK, mapOf("message" to "Slaptažodis pakeistas.")) }
                .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Password reset failed")) }
        }
    }

    get("/password-reset/open") {
        val token = call.request.queryParameters["token"].orEmpty()
        val escapedToken = token
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val appUrl = "skautai://reset-password?token=${java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8)}"
        call.respondText(
            """
            <!doctype html>
            <html lang="lt">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Slaptažodžio atkūrimas</title>
              <meta http-equiv="refresh" content="0;url=$appUrl">
            </head>
            <body style="font-family:sans-serif;max-width:640px;margin:48px auto;padding:20px">
              <h1>Slaptažodžio atkūrimas</h1>
              <p>Atidaroma „Skautų inventoriaus“ programėlė.</p>
              <p><a href="$appUrl" data-token="$escapedToken">Atidaryti programėlę</a></p>
            </body>
            </html>
            """.trimIndent(),
            ContentType.Text.Html
        )
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
