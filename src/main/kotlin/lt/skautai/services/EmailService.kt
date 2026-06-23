package lt.skautai.services

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import lt.skautai.util.LithuanianNameVocativeFormatter

interface EmailService {
    fun sendPasswordReset(to: String, name: String, resetUrl: String): Result<Unit>
    fun sendAccountDeletionConfirmation(to: String, name: String, confirmationUrl: String): Result<Unit>
}

class ResendEmailService : EmailService {
    private val logger = LoggerFactory.getLogger(ResendEmailService::class.java)
    private val json = Json { encodeDefaults = false }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun sendPasswordReset(to: String, name: String, resetUrl: String): Result<Unit> = runCatching {
        val apiKey = setting("RESEND_API_KEY")
            ?: error("RESEND_API_KEY is not configured")
        val from = setting("PASSWORD_RESET_EMAIL_FROM")
            ?: error("PASSWORD_RESET_EMAIL_FROM is not configured")
        val vocativeName = LithuanianNameVocativeFormatter.firstNameVocative(name)
        val safeName = escapeHtml(vocativeName)
        val safeUrl = escapeHtml(resetUrl)
        val payload = ResendEmailRequest(
            from = from,
            to = listOf(to),
            subject = "Slaptažodžio atkūrimas",
            html = """
                <p>Sveiki, $safeName,</p>
                <p>Gavome prašymą atkurti jūsų „Skautų inventoriaus“ paskyros slaptažodį.</p>
                <p><a href="$safeUrl">Atkurti slaptažodį</a></p>
                <p>Nuoroda galioja 1 valandą ir gali būti panaudota tik vieną kartą.</p>
                <p>Jeigu šio prašymo nepateikėte, laišką galite ignoruoti.</p>
            """.trimIndent(),
            text = """
                Sveiki, $vocativeName,

                Atkurkite slaptažodį naudodami šią nuorodą:
                $resetUrl

                Nuoroda galioja 1 valandą ir gali būti panaudota tik vieną kartą.
                Jeigu šio prašymo nepateikėte, laišką galite ignoruoti.
            """.trimIndent()
        )
        val request = HttpRequest.newBuilder(URI("https://api.resend.com/emails"))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(payload)))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Email provider rejected password reset email with status ${response.statusCode()}")
        }
        logger.info("Password reset email accepted by provider for recipient domain={}", to.substringAfter('@', "unknown"))
    }

    override fun sendAccountDeletionConfirmation(
        to: String,
        name: String,
        confirmationUrl: String
    ): Result<Unit> = send(
        to = to,
        subject = "Paskyros ištrynimo patvirtinimas",
        html = deletionHtml(name, confirmationUrl),
        text = deletionText(name, confirmationUrl),
        logLabel = "Account deletion"
    )

    private fun deletionHtml(name: String, confirmationUrl: String): String {
        val safeName = escapeHtml(LithuanianNameVocativeFormatter.firstNameVocative(name))
        val safeUrl = escapeHtml(confirmationUrl)
        return """
            <p>Sveiki, $safeName,</p>
            <p>Gavome prašymą ištrinti jūsų „Skautų inventoriaus“ paskyrą.</p>
            <p><a href="$safeUrl">Peržiūrėti ir patvirtinti paskyros ištrynimą</a></p>
            <p>Nuoroda galioja 1 valandą. Paskyra nebus ištrinta, kol nepatvirtinsite ištrynimo puslapyje.</p>
            <p>Jeigu šio prašymo nepateikėte, laišką galite ignoruoti.</p>
        """.trimIndent()
    }

    private fun deletionText(name: String, confirmationUrl: String): String = """
        Sveiki, ${LithuanianNameVocativeFormatter.firstNameVocative(name)},

        Peržiūrėkite ir patvirtinkite paskyros ištrynimą:
        $confirmationUrl

        Nuoroda galioja 1 valandą. Jeigu šio prašymo nepateikėte, laišką galite ignoruoti.
    """.trimIndent()

    private fun send(
        to: String,
        subject: String,
        html: String,
        text: String,
        logLabel: String
    ): Result<Unit> = runCatching {
        val apiKey = setting("RESEND_API_KEY") ?: error("RESEND_API_KEY is not configured")
        val from = setting("PASSWORD_RESET_EMAIL_FROM") ?: error("PASSWORD_RESET_EMAIL_FROM is not configured")
        val payload = ResendEmailRequest(from, listOf(to), subject, html, text)
        val request = HttpRequest.newBuilder(URI("https://api.resend.com/emails"))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(payload)))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Email provider rejected request with status ${response.statusCode()}")
        }
        logger.info("{} email accepted for recipient domain={}", logLabel, to.substringAfter('@', "unknown"))
    }

    private fun setting(name: String): String? =
        (System.getenv(name) ?: System.getProperty(name))?.trim()?.takeIf(String::isNotBlank)

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

@Serializable
private data class ResendEmailRequest(
    val from: String,
    val to: List<String>,
    val subject: String,
    val html: String,
    val text: String
)
