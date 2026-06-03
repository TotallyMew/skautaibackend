package lt.skautai

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import io.ktor.http.HttpStatusCode
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RolesRoutesTest {

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

    @Test
    fun `roles route validates membership and filters unsupported ranks`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val missingHeader = client.get("/api/roles") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, missingHeader.status)
        assertEquals(
            "Pirmiausia pasirinkite tuntą.",
            Json.parseToJsonElement(missingHeader.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val invalidHeader = client.get("/api/roles") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", "bad-id")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidHeader.status)
        assertEquals(
            "Neteisingas tunto ID.",
            Json.parseToJsonElement(invalidHeader.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        val otherTuntasId = UUID.randomUUID().toString()
        transaction {
            exec(
                """
                INSERT INTO tuntai (id, name, krastas, status)
                VALUES ('$otherTuntasId'::uuid, 'Roles Other Tuntas', 'Vilniaus', 'ACTIVE')
                """.trimIndent()
            )
        }
        val nonMember = client.get("/api/roles") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", otherTuntasId)
        }
        assertEquals(HttpStatusCode.Forbidden, nonMember.status)
        assertEquals(
            "Nesate šio tunto narys.",
            Json.parseToJsonElement(nonMember.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )

        transaction {
            exec(
                """
                INSERT INTO roles (tuntas_id, name, is_system_role, role_type)
                VALUES ('$tuntasId'::uuid, 'Unsupported rank', FALSE, 'RANK')
                """.trimIndent()
            )
        }

        val success = client.get("/api/roles") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, success.status)
        val body = Json.parseToJsonElement(success.bodyAsText()).jsonObject
        val roles = body["roles"]!!.jsonArray.map { it.jsonObject }
        val roleNames = roles.map { it["name"]!!.jsonPrimitive.content }
        assertEquals(roleNames.size, body["total"]!!.jsonPrimitive.content.toInt())
        assertTrue("Tuntininkas" in roleNames)
        assertTrue("Skautas" in roleNames)
        assertTrue("Vadovas" in roleNames)
        assertTrue("Unsupported rank" !in roleNames)
    }
}
