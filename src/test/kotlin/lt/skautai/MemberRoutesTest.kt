package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberRoutesTest {

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

    private suspend fun ApplicationTestBuilder.createUnit(
        token: String,
        tuntasId: String,
        name: String = "Skautai",
        type: String = "SKAUTU_DRAUGOVE",
        subType: String? = null
    ): String {
        val subTypeField = subType?.let { """, "subType": "$it"""" }.orEmpty()
        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "type": "$type"$subTypeField }""")
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.registerUserWithRole(
        token: String,
        tuntasId: String,
        roleName: String,
        email: String,
        organizationalUnitId: String? = null
    ): Pair<String, String> {
        val roleId = TestHelper.getRoleId(tuntasId, roleName)
        val unitField = organizationalUnitId?.let { ", \"organizationalUnitId\": \"$it\"" }.orEmpty()
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId"$unitField, "expiresInHours": 48 }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Test",
                    "surname": "User",
                    "email": "$email",
                    "password": "testas123",
                    "inviteCode": "$inviteCode"
                }
            """.trimIndent())
        }
        val body = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
        return body["token"]!!.jsonPrimitive.content to body["userId"]!!.jsonPrimitive.content
    }


    @Test
    fun `get members returns 200 with tuntininkas`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `get members without token returns 401`() = testApplication {
        configureFullApp()
        val (_, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/members") {
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `inventorininkas can view members and units`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        createUnit(token, tuntasId, "Skautai 1")
        val (inventoryToken, _) = registerUserWithRole(
            token,
            tuntasId,
            "Inventorininkas",
            "inventory-only@test.com"
        )

        val membersResponse = client.get("/api/members") {
            header("Authorization", "Bearer $inventoryToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, membersResponse.status)

        val unitsResponse = client.get("/api/organizational-units") {
            header("Authorization", "Bearer $inventoryToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, unitsResponse.status)
    }

    @Test
    fun `regular member sees own unit but not other unit members`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val ownUnitId = createUnit(token, tuntasId, "Skautai 1")
        val otherUnitId = createUnit(token, tuntasId, "Skautai 2")
        val (memberToken, _) = registerUserWithRole(
            token,
            tuntasId,
            "Skautas",
            "unit-list-member@test.com",
            ownUnitId
        )

        val unitsResponse = client.get("/api/organizational-units") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, unitsResponse.status)
        val units = Json.parseToJsonElement(unitsResponse.bodyAsText()).jsonObject["units"]!!.jsonArray
        val unitIds = units.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        assertTrue(ownUnitId in unitIds)
        assertFalse(otherUnitId in unitIds)

        val otherUnitMembers = client.get("/api/organizational-units/$otherUnitId/members") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.Forbidden, otherUnitMembers.status)
    }

    @Test
    fun `vadovas sees all tuntas members across units`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val firstUnitId = createUnit(token, tuntasId, "Skautai 1")
        val secondUnitId = createUnit(token, tuntasId, "Skautai 2")
        val (vadovasToken, vadovasUserId) = registerUserWithRole(
            token,
            tuntasId,
            "Vadovas",
            "vadovas-members@test.com",
            firstUnitId
        )
        val (_, skautasUserId) = registerUserWithRole(
            token,
            tuntasId,
            "Skautas",
            "skautas-members@test.com",
            secondUnitId
        )

        val response = client.get("/api/members") {
            header("Authorization", "Bearer $vadovasToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val members = Json.parseToJsonElement(response.bodyAsText()).jsonObject["members"]!!.jsonArray
        val memberIds = members.map { it.jsonObject["userId"]!!.jsonPrimitive.content }.toSet()
        assertTrue(vadovasUserId in memberIds)
        assertTrue(skautasUserId in memberIds)
    }

    @Test
    fun `senior unit candidates stay hidden from tuntas leadership until approved`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val seniorUnitId = createUnit(
            token,
            tuntasId,
            name = "Vyr. skautai",
            type = "VYR_SKAUTU_VIENETAS",
            subType = "DRAUGOVE"
        )
        val (seniorLeaderToken, _) = registerUserWithRole(
            token,
            tuntasId,
            "Vyr. skautu draugoves draugininkas",
            "senior-leader@test.com",
            seniorUnitId
        )
        val (_, seniorScoutId) = registerUserWithRole(
            token,
            tuntasId,
            "Vyr. skautas",
            "visible-senior@test.com",
            seniorUnitId
        )
        val (_, candidateId) = registerUserWithRole(
            token,
            tuntasId,
            "Vyr. skautas kandidatas",
            "hidden-candidate@test.com",
            seniorUnitId
        )

        val directoryResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, directoryResponse.status)
        val directoryMembers = Json.parseToJsonElement(directoryResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray
        val directoryIds = directoryMembers
            .map { it.jsonObject["userId"]!!.jsonPrimitive.content }.toSet()
        assertTrue(seniorScoutId in directoryIds)
        assertFalse(candidateId in directoryIds)
        val hiddenDirectoryCandidate = directoryMembers.single {
            it.jsonObject["isIdentityHidden"]?.jsonPrimitive?.content == "true"
        }.jsonObject
        assertEquals("Kandidatas", hiddenDirectoryCandidate["name"]!!.jsonPrimitive.content)
        assertEquals("", hiddenDirectoryCandidate["surname"]!!.jsonPrimitive.content)
        assertEquals("", hiddenDirectoryCandidate["email"]!!.jsonPrimitive.content)

        val unitMembersResponse = client.get("/api/organizational-units/$seniorUnitId/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, unitMembersResponse.status)
        val publicUnitMembers = Json.parseToJsonElement(unitMembersResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray
        val publicUnitMemberIds = publicUnitMembers
            .map { it.jsonObject["userId"]!!.jsonPrimitive.content }.toSet()
        assertTrue(seniorScoutId in publicUnitMemberIds)
        assertFalse(candidateId in publicUnitMemberIds)
        val hiddenUnitCandidate = publicUnitMembers.single {
            it.jsonObject["isIdentityHidden"]?.jsonPrimitive?.content == "true"
        }.jsonObject
        assertEquals("Kandidatas", hiddenUnitCandidate["userName"]!!.jsonPrimitive.content)
        assertEquals("", hiddenUnitCandidate["userSurname"]!!.jsonPrimitive.content)

        val visibilityResponse = client.put(
            "/api/organizational-units/$seniorUnitId/members/$candidateId/visibility"
        ) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $seniorLeaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "isPubliclyVisible": true }""")
        }
        assertEquals(HttpStatusCode.OK, visibilityResponse.status)

        val visibleAfterApproval = client.get("/api/organizational-units/$seniorUnitId/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val visibleIds = Json.parseToJsonElement(visibleAfterApproval.bodyAsText())
            .jsonObject["members"]!!.jsonArray
            .map { it.jsonObject["userId"]!!.jsonPrimitive.content }
            .toSet()
        assertEquals(setOf(seniorScoutId, candidateId), visibleIds)
    }

    @Test
    fun `regular member sees leaders in member list and own unit members only`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val ownUnitId = createUnit(token, tuntasId, "Skautai 1")
        val otherUnitId = createUnit(token, tuntasId, "Skautai 2")
        val (memberToken, memberUserId) = registerUserWithRole(
            token,
            tuntasId,
            "Skautas",
            "own-unit-member@test.com",
            ownUnitId
        )
        registerUserWithRole(
            token,
            tuntasId,
            "Skautas",
            "other-unit-member@test.com",
            otherUnitId
        )

        val ownUnitMembers = client.get("/api/organizational-units/$ownUnitId/members") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, ownUnitMembers.status)

        val memberList = client.get("/api/members") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, memberList.status)
        val members = Json.parseToJsonElement(memberList.bodyAsText()).jsonObject["members"]!!.jsonArray
        assertEquals(1, members.size)
        assertEquals(memberUserId, members[0].jsonObject["userId"]!!.jsonPrimitive.content)

        val otherUnitMembers = client.get("/api/organizational-units/$otherUnitId/members") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.Forbidden, otherUnitMembers.status)
    }

    @Test
    fun `get single member returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        // Get userId from members list
        val listResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        val response = client.get("/api/members/$userId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(userId, body["userId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `get nonexistent member returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/members/00000000-0000-0000-0000-000000000000") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `assign leadership role returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val listResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        val inventorininkaasRoleId = TestHelper.getRoleId(tuntasId, "Inventorininkas")

        val response = client.post("/api/members/$userId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "roleId": "$inventorininkaasRoleId",
                    "startsAt": "2025-01-01T00:00:00Z",
                    "expiresAt": "2026-01-01T00:00:00Z",
                    "termNumber": 1
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Inventorininkas", body["roleName"]?.jsonPrimitive?.content)
        assertEquals("ACTIVE", body["termStatus"]?.jsonPrimitive?.content)
    }

    @Test
    fun `update leadership role term status returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (_, userId) = registerUserWithRole(token, tuntasId, "Skautas", "role-update@test.com")

        val inventorininkaasRoleId = TestHelper.getRoleId(tuntasId, "Inventorininkas")

        val assignResponse = client.post("/api/members/$userId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "roleId": "$inventorininkaasRoleId",
                    "termNumber": 1
                }
            """.trimIndent())
        }

        val assignmentId = Json.parseToJsonElement(assignResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.put("/api/members/$userId/leadership-roles/$assignmentId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "termStatus": "RESIGNED" }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("RESIGNED", body["termStatus"]?.jsonPrimitive?.content)
        assertNotNull(body["leftAt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `principal unit leader is unique and change request transfers slot`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val unitResponse = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Skautai", "type": "SKAUTU_DRAUGOVE" }""")
        }
        val unitId = Json.parseToJsonElement(unitResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val draugininkasRoleId = TestHelper.getRoleId(tuntasId, "Draugininkas")
        val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")

        suspend fun register(email: String): Pair<String, String> {
            val inviteResponse = client.post("/api/invitations") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                header("X-Tuntas-Id", tuntasId)
                setBody("""{ "roleId": "$skautasRoleId" }""")
            }
            val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
            val registerResponse = client.post("/api/auth/register/invite") {
                contentType(ContentType.Application.Json)
                setBody("""{ "name": "Test", "surname": "User", "email": "$email", "password": "testas123", "inviteCode": "$inviteCode" }""")
            }
            val body = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
            return body["token"]!!.jsonPrimitive.content to body["userId"]!!.jsonPrimitive.content
        }

        val (firstToken, firstUserId) = register("first@test.com")
        val (_, secondUserId) = register("second@test.com")

        val firstAssign = client.post("/api/members/$firstUserId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$draugininkasRoleId", "organizationalUnitId": "$unitId" }""")
        }
        assertEquals(HttpStatusCode.Created, firstAssign.status)
        val assignmentId = Json.parseToJsonElement(firstAssign.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val blockedAssign = client.post("/api/members/$secondUserId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$draugininkasRoleId", "organizationalUnitId": "$unitId" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, blockedAssign.status)

        val stepDown = client.post("/api/members/me/leadership-roles/$assignmentId/step-down") {
            header("Authorization", "Bearer $firstToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, stepDown.status)

        val addSuccessorToUnit = client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "MEMBER" }""")
        }
        assertEquals(HttpStatusCode.Created, addSuccessorToUnit.status)

        val resignationRequest = client.post("/api/members/me/leadership-roles/$assignmentId/resignation-request") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $firstToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "reason": "Perduodu pareigas" }""")
        }
        assertEquals(HttpStatusCode.Created, resignationRequest.status)
        val requestId = Json.parseToJsonElement(resignationRequest.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val review = client.post("/api/leadership-change-requests/$requestId/review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "APPROVE", "successorUserId": "$secondUserId" }""")
        }
        assertEquals(HttpStatusCode.OK, review.status)

        val firstReassign = client.post("/api/members/$firstUserId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$draugininkasRoleId", "organizationalUnitId": "$unitId" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, firstReassign.status)
    }

    @Test
    fun `step down hides leadership role but keeps unit assignment`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val unitResponse = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Skautai", "type": "SKAUTU_DRAUGOVE" }""")
        }
        val unitId = Json.parseToJsonElement(unitResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val deputyRoleId = TestHelper.getRoleId(tuntasId, "Draugininko pavaduotojas")

        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$deputyRoleId", "organizationalUnitId": "$unitId" }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""{ "name": "Former", "surname": "Leader", "email": "former@test.com", "password": "testas123", "inviteCode": "$inviteCode" }""")
        }
        val formerLeaderToken = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
        val formerLeaderId = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject["userId"]!!.jsonPrimitive.content

        val memberBeforeStepDown = client.get("/api/members/$formerLeaderId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val assignmentId = Json.parseToJsonElement(memberBeforeStepDown.bodyAsText())
            .jsonObject["leadershipRoles"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val stepDown = client.post("/api/members/me/leadership-roles/$assignmentId/step-down") {
            header("Authorization", "Bearer $formerLeaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, stepDown.status)

        val memberAfterStepDown = client.get("/api/members/$formerLeaderId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, memberAfterStepDown.status)
        val body = Json.parseToJsonElement(memberAfterStepDown.bodyAsText()).jsonObject
        assertEquals(0, body["leadershipRoles"]!!.jsonArray.size)
        assertEquals(1, body["leadershipRoleHistory"]!!.jsonArray.size)
        assertEquals(1, body["unitAssignments"]!!.jsonArray.size)
        assertEquals(unitId, body["unitAssignments"]!!.jsonArray[0].jsonObject["organizationalUnitId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deputy can step down and remains unit member`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)
        val (deputyToken, deputyUserId) = registerUserWithRole(
            token,
            tuntasId,
            "Draugininko pavaduotojas",
            "deputy@test.com",
            unitId
        )

        val deputyDetail = client.get("/api/members/$deputyUserId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val assignmentId = Json.parseToJsonElement(deputyDetail.bodyAsText())
            .jsonObject["leadershipRoles"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val stepDown = client.post("/api/members/me/leadership-roles/$assignmentId/step-down") {
            header("Authorization", "Bearer $deputyToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, stepDown.status)

        val after = client.get("/api/members/$deputyUserId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val body = Json.parseToJsonElement(after.bodyAsText()).jsonObject
        assertEquals(0, body["leadershipRoles"]!!.jsonArray.size)
        assertEquals(1, body["leadershipRoleHistory"]!!.jsonArray.size)
        assertEquals(1, body["unitAssignments"]!!.jsonArray.size)
        assertEquals(unitId, body["unitAssignments"]!!.jsonArray[0].jsonObject["organizationalUnitId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cannot step down another member role or an already resigned role`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)
        val (leaderToken, leaderUserId) = registerUserWithRole(
            token,
            tuntasId,
            "Draugininko pavaduotojas",
            "leader-stepdown@test.com",
            unitId
        )
        val (memberToken, _) = registerUserWithRole(token, tuntasId, "Skautas", "plain-member@test.com")

        val leaderDetail = client.get("/api/members/$leaderUserId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val assignmentId = Json.parseToJsonElement(leaderDetail.bodyAsText())
            .jsonObject["leadershipRoles"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val foreignStepDown = client.post("/api/members/me/leadership-roles/$assignmentId/step-down") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, foreignStepDown.status)

        val firstStepDown = client.post("/api/members/me/leadership-roles/$assignmentId/step-down") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, firstStepDown.status)

        val secondStepDown = client.post("/api/members/me/leadership-roles/$assignmentId/step-down") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, secondStepDown.status)
    }

    @Test
    fun `remove leadership role returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (_, userId) = registerUserWithRole(token, tuntasId, "Skautas", "role-remove@test.com")

        val inventorininkaasRoleId = TestHelper.getRoleId(tuntasId, "Inventorininkas")

        val assignResponse = client.post("/api/members/$userId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$inventorininkaasRoleId", "termNumber": 1 }""")
        }

        val assignmentId = Json.parseToJsonElement(assignResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.delete("/api/members/$userId/leadership-roles/$assignmentId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val memberResponse = client.get("/api/members/$userId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val body = Json.parseToJsonElement(memberResponse.bodyAsText()).jsonObject
        assertFalse(body["leadershipRoles"]!!.jsonArray.any {
            it.jsonObject["id"]!!.jsonPrimitive.content == assignmentId
        })
        val historyRole = body["leadershipRoleHistory"]!!.jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content == assignmentId }
            .jsonObject
        assertEquals("RESIGNED", historyRole["termStatus"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deputy cannot remove tuntininkas leadership role`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (deputyToken, _) = registerUserWithRole(token, tuntasId, "Tuntininko pavaduotojas", "top-deputy@test.com")

        val (tuntininkasUserId, assignmentId) = transaction {
            var userId = ""
            var roleAssignmentId = ""
            exec("""
                SELECT ulr.user_id, ulr.id
                FROM user_leadership_roles ulr
                JOIN roles r ON r.id = ulr.role_id
                WHERE ulr.tuntas_id = '$tuntasId'
                    AND r.name = 'Tuntininkas'
                    AND ulr.left_at IS NULL
                LIMIT 1
            """.trimIndent()) { rs ->
                if (rs.next()) {
                    userId = rs.getString("user_id")
                    roleAssignmentId = rs.getString("id")
                }
            }
            userId to roleAssignmentId
        }

        val response = client.delete("/api/members/$tuntininkasUserId/leadership-roles/$assignmentId") {
            header("Authorization", "Bearer $deputyToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `leader cannot remove equal rank leadership role`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (firstDeputyToken, _) = registerUserWithRole(token, tuntasId, "Tuntininko pavaduotojas", "equal-one@test.com")
        val (_, secondDeputyId) = registerUserWithRole(token, tuntasId, "Tuntininko pavaduotojas", "equal-two@test.com")

        val assignmentId = transaction {
            var id = ""
            exec("""
                SELECT ulr.id
                FROM user_leadership_roles ulr
                JOIN roles r ON r.id = ulr.role_id
                WHERE ulr.user_id = '$secondDeputyId'
                    AND ulr.tuntas_id = '$tuntasId'
                    AND r.name = 'Tuntininko pavaduotojas'
                    AND ulr.left_at IS NULL
                LIMIT 1
            """.trimIndent()) { rs ->
                if (rs.next()) id = rs.getString("id")
            }
            id
        }

        val response = client.delete("/api/members/$secondDeputyId/leadership-roles/$assignmentId") {
            header("Authorization", "Bearer $firstDeputyToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `assign rank returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val listResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")

        val response = client.post("/api/members/$userId/ranks") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$skautasRoleId" }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Skautas", body["roleName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assign leadership role with wrong role type returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val listResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        // Try to assign a RANK role via leadership role endpoint
        val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")

        val response = client.post("/api/members/$userId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$skautasRoleId", "termNumber": 1 }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `remove rank returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val listResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(listResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")

        val assignResponse = client.post("/api/members/$userId/ranks") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$skautasRoleId" }""")
        }

        val rankId = Json.parseToJsonElement(assignResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.delete("/api/members/$userId/ranks/$rankId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }


    @Test
    fun `remove member sets left_at and closes active leadership roles`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        // Register second user via invite
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")
            setBody("""{ "roleId": "$skautasRoleId" }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "name": "Second",
                "surname": "User",
                "email": "second@test.com",
                "password": "testas123",
                "inviteCode": "$inviteCode"
            }
        """.trimIndent())
        }
        val secondUserId = Json.parseToJsonElement(registerResponse.bodyAsText())
            .jsonObject["userId"]!!.jsonPrimitive.content

        // Assign a leadership role to second user
        val inventorininkaasRoleId = TestHelper.getRoleId(tuntasId, "Inventorininkas")
        client.post("/api/members/$secondUserId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$inventorininkaasRoleId", "termNumber": 1 }""")
        }

        val unitResponse = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Skautai", "type": "SKAUTU_DRAUGOVE" }""")
        }
        val unitId = Json.parseToJsonElement(unitResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$secondUserId", "assignmentType": "MEMBER" }""")
        }

        // Remove the member
        val response = client.delete("/api/members/$secondUserId/remove") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Verify membership left_at is set
        transaction {
            exec("SELECT left_at FROM user_tuntas_memberships WHERE user_id = '$secondUserId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
            }
        }

        // Verify active leadership roles are closed
        transaction {
            exec("SELECT term_status, left_at FROM user_leadership_roles WHERE user_id = '$secondUserId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertEquals("RESIGNED", rs.getString("term_status"))
                assertNotNull(rs.getTimestamp("left_at"))
            }
        }

        transaction {
            exec("SELECT left_at FROM unit_assignments WHERE user_id = '$secondUserId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
            }
        }
    }

    @Test
    fun `removed member loses tuntas api access and active unit assignments`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)
        val (memberToken, memberUserId) = registerUserWithRole(token, tuntasId, "Skautas", "removed-access@test.com")

        client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$memberUserId", "assignmentType": "MEMBER" }""")
        }

        val remove = client.delete("/api/members/$memberUserId/remove") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, remove.status)

        val members = client.get("/api/members") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.Forbidden, members.status)

        transaction {
            exec("SELECT left_at FROM user_tuntas_memberships WHERE user_id = '$memberUserId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
            }
            exec("SELECT left_at FROM unit_assignments WHERE user_id = '$memberUserId' AND organizational_unit_id = '$unitId'") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
            }
        }
    }

    @Test
    fun `remove member without permission returns 403`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        // Register second user (Skautas - no members.remove permission)
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")
            setBody("""{ "roleId": "$skautasRoleId" }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val secondTokenResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "name": "Second",
                "surname": "User",
                "email": "second@test.com",
                "password": "testas123",
                "inviteCode": "$inviteCode"
            }
        """.trimIndent())
        }
        val secondToken = Json.parseToJsonElement(secondTokenResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
        val secondUserId = Json.parseToJsonElement(secondTokenResponse.bodyAsText())
            .jsonObject["userId"]!!.jsonPrimitive.content

        // Second user (Skautas) tries to remove the tuntininkas
        val tuntininkaasId = transaction {
            var id = ""
            exec("SELECT user_id FROM user_tuntas_memberships WHERE tuntas_id = '$tuntasId' AND user_id != '$secondUserId' LIMIT 1") { rs ->
                if (rs.next()) id = rs.getString("user_id")
            }
            id
        }

        val response = client.delete("/api/members/$tuntininkaasId/remove") {
            header("Authorization", "Bearer $secondToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `remove nonexistent member returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.delete("/api/members/00000000-0000-0000-0000-000000000000/remove") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `resign removes self from tuntas and closes active leadership roles`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        // Register second user
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")
            setBody("""{ "roleId": "$skautasRoleId" }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val secondResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "name": "Second",
                "surname": "User",
                "email": "second@test.com",
                "password": "testas123",
                "inviteCode": "$inviteCode"
            }
        """.trimIndent())
        }
        val secondToken = Json.parseToJsonElement(secondResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
        val secondUserId = Json.parseToJsonElement(secondResponse.bodyAsText())
            .jsonObject["userId"]!!.jsonPrimitive.content

        // Assign leadership role to second user so we can verify it gets closed
        val inventorininkaasRoleId = TestHelper.getRoleId(tuntasId, "Inventorininkas")
        client.post("/api/members/$secondUserId/leadership-roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$inventorininkaasRoleId", "termNumber": 1 }""")
        }

        // Second user resigns
        val response = client.post("/api/members/$secondUserId/resign") {
            header("Authorization", "Bearer $secondToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        transaction {
            exec("SELECT left_at FROM user_tuntas_memberships WHERE user_id = '$secondUserId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertNotNull(rs.getTimestamp("left_at"))
            }
        }

        transaction {
            exec("SELECT term_status, left_at FROM user_leadership_roles WHERE user_id = '$secondUserId' AND tuntas_id = '$tuntasId'") { rs ->
                assertTrue(rs.next())
                assertEquals("RESIGNED", rs.getString("term_status"))
                assertNotNull(rs.getTimestamp("left_at"))
            }
        }
    }

    @Test
    fun `resign after already being removed returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        // Register second user
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            val skautasRoleId = TestHelper.getRoleId(tuntasId, "Skautas")
            setBody("""{ "roleId": "$skautasRoleId" }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val secondResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "name": "Second",
                "surname": "User",
                "email": "second@test.com",
                "password": "testas123",
                "inviteCode": "$inviteCode"
            }
        """.trimIndent())
        }
        val secondToken = Json.parseToJsonElement(secondResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
        val secondUserId = Json.parseToJsonElement(secondResponse.bodyAsText())
            .jsonObject["userId"]!!.jsonPrimitive.content

        // Admin removes the second user first
        client.delete("/api/members/$secondUserId/remove") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        // Second user now tries to resign â€” should fail as they are no longer active
        val response = client.post("/api/members/$secondUserId/resign") {
            header("Authorization", "Bearer $secondToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `tuntininkas cannot resign from tuntas without transferring role`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/members/me/resign") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Negalite atsistatydinti is tuntininko pareigu"))
    }

    @Test
    fun `tuntininkas cannot step down from leadership role without another active tuntininkas`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val assignmentId = transaction {
            var id = ""
            exec("""
                SELECT ulr.id
                FROM user_leadership_roles ulr
                JOIN roles r ON r.id = ulr.role_id
                WHERE ulr.tuntas_id = '$tuntasId'
                    AND ulr.user_id = (
                        SELECT user_id
                        FROM user_tuntas_memberships
                        WHERE tuntas_id = '$tuntasId' AND left_at IS NULL
                        LIMIT 1
                    )
                    AND r.name = 'Tuntininkas'
                    AND ulr.left_at IS NULL
                LIMIT 1
            """.trimIndent()) { rs ->
                if (rs.next()) id = rs.getString("id")
            }
            id
        }

        val response = client.post("/api/members/me/leadership-roles/$assignmentId/step-down") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Negalite atsistatydinti is tuntininko pareigu"))
    }



}
