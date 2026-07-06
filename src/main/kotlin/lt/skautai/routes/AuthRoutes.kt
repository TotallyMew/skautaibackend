package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.*
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.plugins.EmailRequestRateLimit
import lt.skautai.plugins.PublicAuthRateLimit
import lt.skautai.plugins.RegistrationRateLimit
import lt.skautai.services.AuthService

fun Route.authRoutes(authService: AuthService, apiPrefix: String = "/api") {
    route("$apiPrefix/auth") {
        rateLimit(RegistrationRateLimit) {
            post("/register") {
                val request = call.receiveValidated<RegisterTuntininkasRequest>()
                authService.registerTuntininkas(request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Registracija nepavyko.")) }
            }

            post("/register/invite") {
                val request = call.receiveValidated<RegisterWithInviteRequest>()
                authService.registerWithInvite(request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Registracija nepavyko.")) }
            }
        }

        rateLimit(PublicAuthRateLimit) {
            post("/login") {
                val request = call.receiveValidated<LoginRequest>()
                authService.login(request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.Unauthorized, ErrorResponse(it.message ?: "Prisijungti nepavyko.")) }
            }

            post("/refresh") {
                val request = call.receiveValidated<RefreshTokenRequest>()
                authService.refreshAccessToken(request.refreshToken)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.Unauthorized, ErrorResponse(it.message ?: "Sesijos atnaujinti nepavyko.")) }
            }

            post("/logout") {
                val request = call.receiveValidated<RefreshTokenRequest>()
                authService.logout(request.refreshToken)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/reset-password") {
                val request = call.receiveValidated<ResetPasswordRequest>()
                authService.resetPassword(request)
                    .onSuccess { call.respond(HttpStatusCode.OK, mapOf("message" to "Slaptažodis pakeistas.")) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Slaptažodžio pakeisti nepavyko.")) }
            }
        }

        rateLimit(EmailRequestRateLimit) {
            post("/forgot-password") {
                val request = call.receiveValidated<ForgotPasswordRequest>()
                authService.requestPasswordReset(request)
                    .onSuccess {
                        call.respond(
                            HttpStatusCode.OK,
                            mapOf("message" to "Jei paskyra su šiuo el. paštu egzistuoja, išsiuntėme slaptažodžio atkūrimo nuorodą.")
                        )
                    }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Prašymo pateikti nepavyko.")) }
            }
        }
    }

    if (apiPrefix == "/api") {
        get("/password-reset/open") {
        call.respondText(
            """
            <!doctype html>
            <html lang="lt">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Slaptažodžio atkūrimas</title>
              <meta name="robots" content="noindex">
            </head>
            <body style="font-family:sans-serif;max-width:640px;margin:48px auto;padding:20px">
              <h1>Slaptažodžio atkūrimas</h1>
              <p>Atidaroma „Skautų inventoriaus“ programėlė.</p>
              <p>Atidarykite šią nuorodą telefone, kuriame įdiegta programėlė.</p>
            </body>
            </html>
            """.trimIndent(),
            ContentType.Text.Html
            )
        }
    }

    route("$apiPrefix/setup") {
        rateLimit(RegistrationRateLimit) {
            post("/super-admin") {
                val bootstrapToken = application.environment.config
                    .propertyOrNull("setup.bootstrapToken")?.getString().orEmpty()
                if (bootstrapToken.isBlank()) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                val provided = call.request.headers["X-Bootstrap-Token"].orEmpty()
                if (provided != bootstrapToken) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Neteisingas pradinio nustatymo raktas."))
                    return@post
                }
                val request = call.receiveValidated<LoginRequest>()
                authService.seedSuperAdmin(request)
                    .onSuccess { call.respond(HttpStatusCode.Created, it) }
                    .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Pradinio nustatymo atlikti nepavyko.")) }
            }
        }
    }

    route("$apiPrefix/super-admin") {
        rateLimit(PublicAuthRateLimit) {
            post("/login") {
                val request = call.receiveValidated<LoginRequest>()
                authService.loginSuperAdmin(request)
                    .onSuccess { call.respond(HttpStatusCode.OK, it) }
                    .onFailure { call.respond(HttpStatusCode.Unauthorized, ErrorResponse(it.message ?: "Prisijungti nepavyko.")) }
            }
        }
    }
}
