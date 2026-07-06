package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.getRoleId
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SuperAdminRoutesTest {
    private val bootstrapToken = "test-bootstrap-token"

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

    private suspend fun ApplicationTestBuilder.seedAndLoginSuperAdmin(): String {
        client.post("/api/setup/super-admin") {
            contentType(ContentType.Application.Json)
            header("X-Bootstrap-Token", bootstrapToken)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }

        val loginResponse = client.post("/api/super-admin/login") {
            contentType(ContentType.Application.Json)
            setBody("""{ "email": "admin@test.com", "password": "admin123" }""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)

        return Json.parseToJsonElement(loginResponse.bodyAsText())
            .jsonObject["token"]!!
            .jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.inviteAndRegisterMember(
        token: String,
        tuntasId: String,
        roleName: String,
        email: String
    ): String {
        val roleId = getRoleId(tuntasId, roleName)
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId", "expiresInHours": 48 }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!
            .jsonPrimitive.content

        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "name": "Second",
                    "surname": "User",
                    "email": "$email",
                    "password": "testas123",
                    "inviteCode": "$inviteCode"
                }
                """.trimIndent()
            )
        }

        return Json.parseToJsonElement(registerResponse.bodyAsText())
            .jsonObject["userId"]!!
            .jsonPrimitive.content
    }

    @Test
    fun `super admin can delete tuntas`() = testApplication {
        configureFullApp()

        val adminToken = seedAndLoginSuperAdmin()
        val (_, tuntasId) = client.registerAndActivateTuntininkas(
            email = "delete-me@test.com",
            tuntasName = "Delete Me Tuntas"
        )

        val deleteResponse = client.delete("/api/super-admin/tuntai/$tuntasId") {
            header("Authorization", "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        val listResponse = client.get("/api/super-admin/tuntai") {
            header("Authorization", "Bearer $adminToken")
        }
        val deletedTuntas = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonArray
            .firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == tuntasId }

        assertNotNull(deletedTuntas)
        assertEquals("DELETED", deletedTuntas!!.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `super admin can assign leadership role in any tuntas`() = testApplication {
        configureFullApp()

        val adminToken = seedAndLoginSuperAdmin()
        val (tuntininkasToken, tuntasId) = client.registerAndActivateTuntininkas(
            email = "leader@test.com",
            tuntasName = "Admin Managed Tuntas"
        )
        val memberId = inviteAndRegisterMember(
            token = tuntininkasToken,
            tuntasId = tuntasId,
            roleName = "Skautas",
            email = "member@test.com"
        )
        val roleId = getRoleId(tuntasId, "Inventorininkas")

        val assignResponse = client.post("/api/super-admin/tuntai/$tuntasId/members/$memberId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $adminToken")
            setBody("""{ "roleId": "$roleId" }""")
        }

        assertEquals(HttpStatusCode.Created, assignResponse.status)

        val memberResponse = client.get("/api/super-admin/tuntai/$tuntasId/members/$memberId") {
            header("Authorization", "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, memberResponse.status)
        val leadershipRoles = Json.parseToJsonElement(memberResponse.bodyAsText())
            .jsonObject["leadershipRoles"]!!
            .toString()

        assertTrue(leadershipRoles.contains("Inventorininkas"))
    }
}
