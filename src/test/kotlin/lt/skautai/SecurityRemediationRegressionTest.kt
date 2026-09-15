package lt.skautai

import com.auth0.jwt.JWT
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.withTimeout
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.TestHelper.registerInvitedUser
import lt.skautai.TestHelper.randomEmail
import lt.skautai.database.tables.*
import lt.skautai.models.requests.CreateItemRequest
import lt.skautai.plugins.resolveUserPermissions
import lt.skautai.routes.*
import lt.skautai.services.*
import lt.skautai.util.UploadStorage
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import java.io.File
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityRemediationRegressionTest {
    @Test fun `password reset revokes a concurrent refresh and its replacement`() = testApplication {
        app()
        lateinit var auth: AuthService
        application { auth = AuthService(environment) }
        val email = randomEmail("reset-race")
        client.registerAndActivateTuntininkas(email = email)
        val session = client.login(email)
        val token = session.getValue("token").jsonPrimitive.content
        val refresh = session.getValue("refreshToken").jsonPrimitive.content
        client.post("/api/auth/forgot-password") {
            contentType(ContentType.Application.Json); setBody("""{"email":"$email"}""")
        }
        val reset = TestHelper.lastPasswordResetLink!!.substringAfter("token=").substringBefore('&')
        val executor = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val renewal = executor.submit(Callable {
                barrier.await(10, TimeUnit.SECONDS)
                auth.refreshAccessToken(refresh)
            })
            val resetResult = executor.submit(Callable {
                barrier.await(10, TimeUnit.SECONDS)
                auth.resetPassword(lt.skautai.models.requests.ResetPasswordRequest(reset, "newPassword123"))
            })
            assertTrue(resetResult.get(20, TimeUnit.SECONDS).isSuccess)
            val renewed = renewal.get(20, TimeUnit.SECONDS)
            assertEquals(0L, transaction { AuthRefreshSessions.selectAll().where {
                (AuthRefreshSessions.subjectId eq id(token)) and AuthRefreshSessions.revokedAt.isNull()
            }.count() })
            renewed.getOrNull()?.let { assertFalse(lt.skautai.plugins.isAccessSessionActive(JWT.decode(it.token))) }
        } finally { executor.shutdownNow() }
    }

    @Test fun `already open live stream ends after logout on both aliases`() = testApplication {
        app()
        val email = randomEmail("live-revocation")
        val (_, tenant) = client.registerAndActivateTuntininkas(email = email)
        for (prefix in listOf("/api", "/api/v1")) {
            val session = client.login(email)
            val token = session.getValue("token").jsonPrimitive.content
            val refresh = session.getValue("refreshToken").jsonPrimitive.content
            withTimeout(12_000) {
                client.prepareGet("$prefix/live/events") { bearerAuth(token); header("X-Tuntas-Id", tenant) }.execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val channel = response.bodyAsChannel()
                    assertEquals("retry: 5000", channel.readUTF8Line())
                    client.post("/api/auth/logout") {
                        contentType(ContentType.Application.Json); setBody("""{"refreshToken":"$refresh"}""")
                    }
                    while (true) {
                        val line = channel.readUTF8Line() ?: break
                        assertFalse(line.startsWith("data:"), "Revoked stream sent an event")
                    }
                }
            }
        }
    }
    @Test fun `logout follows refresh rotation without revoking another login`() = testApplication {
        app()
        val email = randomEmail("rotation")
        client.registerAndActivateTuntininkas(email = email)
        val first = client.login(email)
        val second = client.login(email)
        val originalRefresh = first.getValue("refreshToken").jsonPrimitive.content
        val rotatedResponse = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json); setBody("""{"refreshToken":"$originalRefresh"}""")
        }
        assertEquals(HttpStatusCode.OK, rotatedResponse.status)
        val rotated = json(rotatedResponse.bodyAsText()).getValue("token").jsonPrimitive.content
        client.post("/api/auth/logout") {
            contentType(ContentType.Application.Json); setBody("""{"refreshToken":"$originalRefresh"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/users/me") { bearerAuth(rotated) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/users/me") { bearerAuth(second.getValue("token").jsonPrimitive.content) }.status)
    }

    @Test fun `ordinary profile edits cannot redirect recovery email across aliases`() = testApplication {
        app()
        val email = randomEmail("immutable-email")
        val (token, _) = client.registerAndActivateTuntininkas(email = email)
        for (prefix in listOf("/api", "/api/v1")) {
            assertEquals(HttpStatusCode.BadRequest, client.put("$prefix/users/me/profile") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody("""{"name":"Changed","surname":"User","email":"attacker@example.com","phone":null}""")
            }.status)
            assertEquals(email, transaction { Users.selectAll().where { Users.id eq id(token) }.first()[Users.email] })
        }
    }
    @BeforeAll fun setup() = TestHelper.setupDatabase()
    @AfterAll fun teardown() = TestHelper.teardownDatabase()
    @BeforeEach fun clean() = TestHelper.cleanTables()
    @AfterEach fun clearLimits() { System.clearProperty("UPLOAD_USER_FILES") }

    private fun ApplicationTestBuilder.app() {
        configureFullApp()
        application { routing {
            userRoutes("/api/v1")
            itemRoutes(ItemService(), ItemCheckService(), "/api/v1")
            uploadRoutes("/api/v1")
            liveEventRoutes()
            liveEventRoutes("/api/v1")
        } }
    }
    private fun id(token: String): UUID = UUID.fromString(JWT.decode(token).getClaim("userId").asString())
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private suspend fun io.ktor.client.HttpClient.login(email: String): JsonObject {
        val response = post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"testas123"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return json(response.bodyAsText())
    }
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private suspend fun io.ktor.client.HttpClient.upload(token: String, tenant: String? = null, prefix: String = "/api"): Pair<HttpStatusCode, String> {
        val response = post("$prefix/uploads/images") {
            bearerAuth(token); tenant?.let { header("X-Tuntas-Id", it) }
            setBody(TestHelper.multiPartForFile(fileName = "photo.png", contentType = ContentType.Image.PNG, bytes = png))
        }
        return response.status to response.bodyAsText()
    }
    private fun stock(tenant: UUID, actor: UUID, name: String, photo: String) =
        ItemService().createItem(tenant, actor, CreateItemRequest(name, type = "COLLECTIVE", category = "TOOLS", quantity = 1, photoUrl = photo))

    @Test fun `inactive tenants cannot use operations with or without headers across aliases`() = testApplication {
        app()
        val (token, tenantText) = client.registerAndActivateTuntininkas()
        val tenant = UUID.fromString(tenantText)
        for (state in listOf("PENDING", "REJECTED", "DELETED")) {
            transaction { Tuntai.update({ Tuntai.id eq tenant }) { it[status] = state } }
            for (prefix in listOf("/api", "/api/v1")) {
                assertEquals(HttpStatusCode.Forbidden, client.get("$prefix/items") { bearerAuth(token); header("X-Tuntas-Id", tenantText) }.status)
                assertEquals(HttpStatusCode.Forbidden, client.upload(token, prefix = prefix).first)
                assertEquals(HttpStatusCode.OK, client.get("$prefix/users/me") { bearerAuth(token) }.status)
            }
            assertTrue(resolveUserPermissions(id(token), tenant).isEmpty())
        }
        assertEquals(0L, transaction { StoredUploads.selectAll().count() })
        transaction { Tuntai.update({ Tuntai.id eq tenant }) { it[status] = "ACTIVE" } }
        assertEquals(HttpStatusCode.Created, client.upload(token).first)
    }

    @Test fun `role date boundaries and direct candidate visibility reject expired and future leaders`() = testApplication {
        app()
        val (token, tenantText) = client.registerAndActivateTuntininkas()
        val tenant = UUID.fromString(tenantText)
        val actor = id(token)
        val (_, memberText) = client.registerInvitedUser(token, tenantText, "Skautas", randomEmail("candidate"))
        val member = UUID.fromString(memberText)
        val now = Clock.System.now()
        val unit = transaction {
            OrganizationalUnits.insert {
                it[tuntasId] = tenant; it[name] = "Private senior unit"; it[type] = "VYR_SKAUTU_VIENETAS"
                it[createdAt] = now; it[updatedAt] = now
            } get OrganizationalUnits.id
        }
        val assignment = transaction {
            val leaderRole = Roles.selectAll().where { (Roles.tuntasId eq tenant) and (Roles.name eq "Vyr. skautu draugoves draugininkas") }.first()[Roles.id]
            val candidateRank = Roles.selectAll().where { (Roles.tuntasId eq tenant) and (Roles.name eq "Vyr. skautas kandidatas") }.first()[Roles.id]
            UserRanks.update({ (UserRanks.userId eq member) and (UserRanks.tuntasId eq tenant) }) { it[roleId] = candidateRank }
            UnitAssignments.insert {
                it[userId] = member; it[organizationalUnitId] = unit; it[tuntasId] = tenant; it[joinedAt] = now
            }
            UserLeadershipRoles.insert {
                it[userId] = actor; it[tuntasId] = tenant; it[organizationalUnitId] = unit; it[roleId] = leaderRole
                it[startsAt] = now; it[expiresAt] = now + 1.hours
            } get UserLeadershipRoles.id
        }
        transaction {
            assertEquals(1L, UserLeadershipRoles.selectAll().where { (UserLeadershipRoles.id eq assignment) and UserLeadershipRoles.effectiveNow(now) }.count())
            assertEquals(0L, UserLeadershipRoles.selectAll().where { (UserLeadershipRoles.id eq assignment) and UserLeadershipRoles.effectiveNow(now + 1.hours) }.count())
        }
        for (future in listOf(true, false)) {
            transaction { UserLeadershipRoles.update({ UserLeadershipRoles.id eq assignment }) {
                it[startsAt] = if (future) now + 1.hours else now - 2.hours
                it[expiresAt] = if (future) now + 2.hours else now - 1.hours
            } }
            assertFalse(SeniorUnitPrivacyService.canManageCandidateVisibility(actor, tenant, unit))
            val response = client.put("/api/organizational-units/$unit/members/$member/visibility") {
                bearerAuth(token); header("X-Tuntas-Id", tenantText); contentType(ContentType.Application.Json)
                setBody("""{"isPubliclyVisible":true}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertFalse(transaction { UnitAssignments.selectAll().where { UnitAssignments.userId eq member }.first()[UnitAssignments.isPubliclyVisible] })
        }
        transaction { UserLeadershipRoles.update({ UserLeadershipRoles.id eq assignment }) { it[startsAt] = now - 1.hours; it[expiresAt] = now + 1.hours } }
        assertTrue(SeniorUnitPrivacyService.canManageCandidateVisibility(actor, tenant, unit))
        assertEquals(HttpStatusCode.OK, client.put("/api/organizational-units/$unit/members/$member/visibility") {
            bearerAuth(token); header("X-Tuntas-Id", tenantText); contentType(ContentType.Application.Json)
            setBody("""{"isPubliclyVisible":true}""")
        }.status)
    }

    @Test fun `previously issued access tokens and live authority stop after logout reset and tenant deletion`() = testApplication {
        app()
        val email = randomEmail("session")
        val (_, tenantText) = client.registerAndActivateTuntininkas(email = email)
        val tenant = UUID.fromString(tenantText)
        val session = client.login(email)
        val token = session.getValue("token").jsonPrimitive.content
        val refresh = session.getValue("refreshToken").jsonPrimitive.content
        val actor = id(token)
        val original = resolveUserPermissions(actor, tenant)
        assertTrue(liveAccessStillValid(JWT.decode(token), actor, tenant, original))
        assertEquals(HttpStatusCode.NoContent, client.post("/api/auth/logout") {
            contentType(ContentType.Application.Json); setBody("""{"refreshToken":"$refresh"}""")
        }.status)
        for (prefix in listOf("/api", "/api/v1")) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("$prefix/users/me") { bearerAuth(token) }.status)
        }
        assertFalse(liveAccessStillValid(JWT.decode(token), actor, tenant, original))
        val fresh = client.login(email).getValue("token").jsonPrimitive.content
        client.post("/api/auth/forgot-password") { contentType(ContentType.Application.Json); setBody("""{"email":"$email"}""") }
        val resetToken = TestHelper.lastPasswordResetLink!!.substringAfter("token=").substringBefore('&')
        assertEquals(HttpStatusCode.OK, client.post("/api/auth/reset-password") {
            contentType(ContentType.Application.Json); setBody("""{"token":"$resetToken","newPassword":"differentPassword123"}""")
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/users/me") { bearerAuth(fresh) }.status)
        assertFalse(liveAccessStillValid(JWT.decode(fresh), actor, tenant, original))
    }

    @Test fun `file reads bindings and deletion respect ownership and existing references`() = testApplication {
        app()
        val (aToken, aText) = client.registerAndActivateTuntininkas(email = randomEmail("a"), tuntasName = "Alpha")
        val (bToken, bText) = client.registerAndActivateTuntininkas(email = randomEmail("b"), tuntasName = "Beta")
        val aTenant = UUID.fromString(aText); val bTenant = UUID.fromString(bText)
        val (status, uploaded) = client.upload(aToken, aText)
        assertEquals(HttpStatusCode.Created, status)
        val url = json(uploaded).getValue("url").jsonPrimitive.content
        val file = UploadStorage.resolveImage(url.substringAfterLast('/'))!!
        assertEquals(HttpStatusCode.OK, client.get(url) { bearerAuth(aToken); header("X-Tuntas-Id", aText) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(url) { bearerAuth(bToken); header("X-Tuntas-Id", bText) }.status)
        assertTrue(stock(bTenant, id(bToken), "Foreign", url).isFailure)
        assertEquals(0L, transaction { Items.selectAll().count() })
        val first = stock(aTenant, id(aToken), "First", url).getOrThrow()
        val second = stock(aTenant, id(aToken), "Second", url).getOrThrow()
        assertTrue(ItemService().deleteItem(UUID.fromString(first.id), aTenant, id(aToken)).isSuccess)
        assertTrue(file.exists())
        assertTrue(ItemService().getItem(UUID.fromString(second.id), aTenant, id(aToken)).isSuccess)
        assertEquals(HttpStatusCode.OK, client.get(url) { bearerAuth(aToken); header("X-Tuntas-Id", aText) }.status)
        assertTrue(stock(aTenant, id(aToken), "Forged", "/uploads/images/unknown.png").isFailure)
    }

    @Test fun `quota reservations serialize across concurrent upload attempts`() = testApplication {
        app()
        val (token, tenantText) = client.registerAndActivateTuntininkas()
        val tenant = UUID.fromString(tenantText); val actor = id(token)
        System.setProperty("UPLOAD_USER_FILES", "1")
        val executor = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val tasks = (1..2).map { executor.submit(Callable {
                barrier.await(10, TimeUnit.SECONDS)
                runCatching { UploadService.reserve(actor, tenant, "IMAGE", 100) }
            }) }
            val results = tasks.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertTrue(results.first { it.isFailure }.exceptionOrNull() is UploadRejected)
            assertEquals(1L, transaction { StoredUploads.selectAll().where { StoredUploads.state eq "RECEIVING" }.count() })
            results.mapNotNull { it.getOrNull() }.forEach(UploadService::abandon)
            assertTrue(runCatching { UploadService.reserve(actor, tenant, "IMAGE", 100) }.isSuccess)
        } finally { executor.shutdownNow() }
    }

    @Test fun `cleanup expires only recorded unattached uploads and denied transactions preserve binding`() = testApplication {
        app()
        val (token, tenantText) = client.registerAndActivateTuntininkas()
        val tenant = UUID.fromString(tenantText); val actor = id(token)
        val loose = json(client.upload(token, tenantText).second).getValue("url").jsonPrimitive.content
        val bound = json(client.upload(token, tenantText).second).getValue("url").jsonPrimitive.content
        assertFailsWith<IllegalStateException> { transaction {
            assertNull(UploadService.authorizeBinding(loose, tenant, actor, "IMAGE"))
            error("simulate database rollback")
        } }
        assertNull(transaction { StoredUploads.selectAll().where { StoredUploads.fileUrl eq loose }.first()[StoredUploads.attachedAt] })
        stock(tenant, actor, "Retained", bound).getOrThrow()
        val legacy = File(UploadStorage.imagesDir(), "legacy-unchanged.png").apply { writeBytes(png) }
        transaction { StoredUploads.update({ StoredUploads.tuntasId eq tenant }) { it[createdAt] = Clock.System.now() - 48.hours } }
        UploadService.cleanupUnattached()
        assertFalse(UploadStorage.resolveImage(loose.substringAfterLast('/'))!!.exists())
        assertTrue(UploadStorage.resolveImage(bound.substringAfterLast('/'))!!.exists())
        assertTrue(legacy.exists())
        assertEquals("READY", transaction { StoredUploads.selectAll().where { StoredUploads.fileUrl eq bound }.first()[StoredUploads.state] })
    }
}
