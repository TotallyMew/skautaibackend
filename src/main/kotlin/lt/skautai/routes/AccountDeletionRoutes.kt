package lt.skautai.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import lt.skautai.models.requests.PublicAccountDeletionRequest
import lt.skautai.models.requests.RequestAccountDeletionRequest
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.models.responses.MessageResponse
import lt.skautai.plugins.EmailRequestRateLimit
import lt.skautai.plugins.PublicAuthRateLimit
import lt.skautai.services.AccountDeletionService
import lt.skautai.services.TokenStatus
import java.util.UUID

fun Route.accountDeletionRoutes(service: AccountDeletionService, apiPrefix: String = "/api") {
    authenticate("auth-jwt") {
        rateLimit(EmailRequestRateLimit) {
            post("$apiPrefix/users/me/account-deletion") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.getClaim("userId", String::class))
                val request = call.receiveValidated<RequestAccountDeletionRequest>()
                service.requestFromApp(userId, request.password)
                    .onSuccess {
                        call.respond(
                            HttpStatusCode.Accepted,
                            MessageResponse("Patvirtinimo nuoroda išsiųsta į jūsų el. paštą.")
                        )
                    }
                    .onFailure {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Nepavyko pateikti prašymo."))
                    }
            }
        }
    }

    rateLimit(EmailRequestRateLimit) {
        post("$apiPrefix/account-deletion/request") {
            val request = call.receiveValidated<PublicAccountDeletionRequest>()
            service.requestFromWeb(request.email)
            call.respond(
                HttpStatusCode.Accepted,
                MessageResponse("Jei paskyra su šiuo el. paštu egzistuoja, išsiuntėme patvirtinimo nuorodą.")
            )
        }

        if (apiPrefix == "/api") {
            post("/account-deletion/request") {
            val email = call.receiveParameters()["email"].orEmpty()
            service.requestFromWeb(email)
            call.respondText(
                accountDeletionResultPage(
                    "Prašymas priimtas",
                    "Jei paskyra su šiuo el. paštu egzistuoja, išsiuntėme patvirtinimo nuorodą."
                ),
                ContentType.Text.Html,
                HttpStatusCode.Accepted
            )
            }
        }
    }

    if (apiPrefix == "/api") {
        get("/account-deletion/confirm") {
        val token = call.request.queryParameters["token"].orEmpty()
        val body = when (service.tokenStatus(token)) {
            TokenStatus.VALID -> accountDeletionConfirmationPage(token)
            TokenStatus.EXPIRED -> accountDeletionResultPage("Nuoroda nebegalioja", "Pateikite naują paskyros ištrynimo prašymą.")
            TokenStatus.USED -> accountDeletionResultPage("Nuoroda jau panaudota", "Šis paskyros ištrynimo prašymas jau užbaigtas.")
            TokenStatus.INVALID -> accountDeletionResultPage("Nuoroda netinkama", "Patikrinkite, ar nukopijavote visą nuorodą.")
        }
        call.respondText(body, ContentType.Text.Html)
        }

        rateLimit(PublicAuthRateLimit) {
            post("/account-deletion/confirm") {
            val token = call.receiveParameters()["token"].orEmpty()
            service.confirm(token)
                .onSuccess {
                    call.respondText(
                        accountDeletionResultPage(
                            "Paskyra ištrinta",
                            "Jūsų prisijungimas, asmeniniai duomenys ir aktyvios narystės pašalinti."
                        ),
                        ContentType.Text.Html
                    )
                }
                .onFailure {
                    call.respondText(
                        accountDeletionResultPage("Nepavyko ištrinti paskyros", it.message ?: "Pabandykite dar kartą."),
                        ContentType.Text.Html,
                        HttpStatusCode.BadRequest
                    )
                }
            }
        }
    }
}

private fun accountDeletionConfirmationPage(token: String): String = page(
    "Patvirtinkite paskyros ištrynimą",
    """
        <p>Bus pašalinti jūsų prisijungimo duomenys, kontaktinė informacija, aktyvios narystės, rolės ir įrenginių duomenys.</p>
        <p>Anonimizuoti inventoriaus ir veiksmų audito įrašai gali būti išsaugoti bendro turto apskaitos vientisumui.</p>
        <p><strong>Šio veiksmo atšaukti negalima.</strong></p>
        <form method="post" action="/account-deletion/confirm">
          <input type="hidden" name="token" value="${escapeHtml(token)}">
          <button class="danger" type="submit">Visam laikui ištrinti paskyrą</button>
        </form>
    """.trimIndent()
)

private fun accountDeletionResultPage(title: String, message: String): String =
    page(title, "<p>${escapeHtml(message)}</p>")

private fun page(title: String, content: String): String = """
<!doctype html>
<html lang="lt"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(title)} – Skautų inventorius</title>
<style>
body{font-family:system-ui,sans-serif;background:#f3f6f2;color:#172019;margin:0;padding:24px}
main{max-width:620px;margin:8vh auto;background:white;padding:32px;border-radius:18px;box-shadow:0 10px 30px #10201518}
h1{font-size:1.7rem}.danger{border:0;border-radius:10px;padding:13px 18px;background:#b42318;color:white;font-weight:700;cursor:pointer}
</style></head><body><main><h1>${escapeHtml(title)}</h1>$content</main></body></html>
""".trimIndent()

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&#39;")
