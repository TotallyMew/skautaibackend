package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.createUnit
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.TestHelper.registerInvitedUser
import lt.skautai.plugins.resolveUserPermissions
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityTest {

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
    fun `resolveUserPermissions returns empty for non member`() {
        val permissions = resolveUserPermissions(UUID.randomUUID(), UUID.randomUUID())
        assertTrue(permissions.isEmpty())
    }

    @Test
    fun `resolveUserPermissions merges rank and leadership permissions with unit scope`() = testApplication {
        configureFullApp()
        val (leaderToken, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(leaderToken, tuntasId, "Security Merge Unit")
        val (memberToken, userId) = client.registerInvitedUser(
            inviterToken = leaderToken,
            tuntasId = tuntasId,
            roleName = "Skautas",
            email = TestHelper.randomEmail("security-merge"),
            organizationalUnitId = unitId
        )
        val deputyRoleId = TestHelper.getRoleId(tuntasId, "Draugininko pavaduotojas")

        transaction {
            exec(
                """
                INSERT INTO user_leadership_roles (
                    user_id, role_id, tuntas_id, organizational_unit_id, assigned_by_user_id, term_status
                ) VALUES (
                    '$userId'::uuid,
                    '$deputyRoleId'::uuid,
                    '$tuntasId'::uuid,
                    '$unitId'::uuid,
                    (SELECT id FROM users WHERE email = 'tuntininkas@test.com' LIMIT 1),
                    'ACTIVE'
                )
                """.trimIndent()
            )
        }

        val resolved = resolveUserPermissions(UUID.fromString(userId), UUID.fromString(tuntasId))
        assertTrue(resolved.any { it.permissionName == "members.view" && it.scope == "OWN_UNIT" })
        assertTrue(resolved.any { it.permissionName == "invitations.create" && it.scope == "OWN_UNIT" })
        assertTrue(resolved.any { UUID.fromString(unitId) in it.userOrgUnitIds })

        val ownUnitInvite = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "${TestHelper.getRoleId(tuntasId, "Skautas")}", "organizationalUnitId": "$unitId", "expiresInHours": 48 }""")
        }
        assertEquals(HttpStatusCode.Created, ownUnitInvite.status)
    }

    @Test
    fun `all scoped permission bypasses target org unit restriction`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Any Unit")

        val response = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "${TestHelper.getRoleId(tuntasId, "Skautas")}", "organizationalUnitId": "$unitId", "expiresInHours": 48 }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `empty own unit scope is rejected by checkPermission`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas(email = "scope@test.com")
        val unitId = createUnit(token, tuntasId, "Scoped Target")
        val (memberToken, userId) = client.registerInvitedUser(
            inviterToken = token,
            tuntasId = tuntasId,
            roleName = "Skautas",
            email = TestHelper.randomEmail("scoped-member"),
            organizationalUnitId = unitId
        )
        val draugininkasRoleId = TestHelper.getRoleId(tuntasId, "Draugininkas")

        transaction {
            exec("DELETE FROM unit_assignments WHERE user_id = '$userId'::uuid AND tuntas_id = '$tuntasId'::uuid")
            exec("DELETE FROM user_ranks WHERE user_id = '$userId'::uuid AND tuntas_id = '$tuntasId'::uuid")
            exec(
                """
                INSERT INTO user_leadership_roles (
                    user_id, role_id, tuntas_id, organizational_unit_id, assigned_by_user_id, term_status
                ) VALUES (
                    '$userId'::uuid,
                    '$draugininkasRoleId'::uuid,
                    '$tuntasId'::uuid,
                    NULL,
                    (SELECT id FROM users WHERE email = 'scope@test.com' LIMIT 1),
                    'ACTIVE'
                )
                """.trimIndent()
            )
        }

        val response = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "${TestHelper.getRoleId(tuntasId, "Skautas")}", "organizationalUnitId": "$unitId", "expiresInHours": 48 }""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Neturite teisių atlikti šį veiksmą.", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `scoped own unit permission rejects other units`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val ownUnitId = createUnit(token, tuntasId, "Own Unit")
        val otherUnitId = createUnit(token, tuntasId, "Other Unit")
        val (memberToken, _) = client.registerInvitedUser(
            inviterToken = token,
            tuntasId = tuntasId,
            roleName = "Draugininkas",
            email = TestHelper.randomEmail("own-unit-only"),
            organizationalUnitId = ownUnitId
        )

        val response = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "${TestHelper.getRoleId(tuntasId, "Skautas")}", "organizationalUnitId": "$otherUnitId", "expiresInHours": 48 }""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Neturite teisių atlikti šį veiksmą.", body["error"]!!.jsonPrimitive.content)
    }
}
