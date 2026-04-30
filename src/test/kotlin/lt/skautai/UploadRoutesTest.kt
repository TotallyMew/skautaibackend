package lt.skautai

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.multiPartForFile
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadRoutesTest {

    @BeforeAll
    fun setup() {
        TestHelper.setupDatabase()
    }

    @AfterAll
    fun teardown() {
        TestHelper.teardownDatabase()
    }

    @BeforeEach
    fun cleanTables() {
        TestHelper.cleanTables()
    }

    private fun parseError(responseBody: String): String =
        Json.parseToJsonElement(responseBody).jsonObject["error"]!!.jsonPrimitive.content

    @Test
    fun `upload routes validate files and support successful upload download`() = testApplication {
        configureFullApp()
        val (token, _) = client.registerAndActivateTuntininkas()

        val validImage = client.post("/api/uploads/images") {
            header("Authorization", "Bearer $token")
            setBody(multiPartForFile(fileName = "valid.png", contentType = ContentType.Image.PNG, bytes = pngBytes()))
        }
        assertEquals(HttpStatusCode.Created, validImage.status)
        val imageUrl = Json.parseToJsonElement(validImage.bodyAsText()).jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(imageUrl.startsWith("/uploads/images/"))

        val validPdf = client.post("/api/uploads/documents") {
            header("Authorization", "Bearer $token")
            setBody(
                multiPartForFile(
                    fileName = "valid.pdf",
                    contentType = ContentType.Application.Pdf,
                    bytes = pdfBytes()
                )
            )
        }
        assertEquals(HttpStatusCode.Created, validPdf.status)
        val documentUrl = Json.parseToJsonElement(validPdf.bodyAsText()).jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(documentUrl.startsWith("/uploads/documents/"))

        val download = client.get(imageUrl) {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, download.status)
        assertEquals(pngBytes().size.toString(), download.headers[HttpHeaders.ContentLength])

        val missingFile = client.get("/uploads/images/does-not-exist.png") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, missingFile.status)
        assertEquals("File not found", parseError(missingFile.bodyAsText()))
    }

    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private fun pdfBytes(): ByteArray = "%PDF-1.4".toByteArray(Charsets.US_ASCII)
}
