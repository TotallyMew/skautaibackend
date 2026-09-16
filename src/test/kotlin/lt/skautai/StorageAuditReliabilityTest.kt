package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorageAuditReliabilityTest {
    @BeforeAll fun setup() = TestHelper.setupDatabase()
    @AfterAll fun teardown() = TestHelper.teardownDatabase()
    @BeforeEach fun clean() = TestHelper.cleanTables()
    private fun String.obj() = Json.parseToJsonElement(this).jsonObject
    private fun JsonObject.id() = getValue("id").jsonPrimitive.content
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.content.toInt()
    private suspend fun ApplicationTestBuilder.post(token: String, tuntas: String, path: String, body: String) =
        client.post(path) { contentType(ContentType.Application.Json); bearerAuth(token); header("X-Tuntas-Id",tuntas); setBody(body) }
    private suspend fun ApplicationTestBuilder.get(token: String, tuntas: String, path: String) =
        client.get(path) { bearerAuth(token); header("X-Tuntas-Id",tuntas) }
    private suspend fun ApplicationTestBuilder.item(token: String, tuntas: String, name: String = "Kirvis", qty: Int = 10): String =
        post(token,tuntas,"/api/items","""{"name":"$name","type":"COLLECTIVE","category":"TOOLS","quantity":$qty}""").bodyAsText().obj().id()
    private suspend fun ApplicationTestBuilder.start(token: String, tuntas: String): JsonObject =
        post(token,tuntas,"/api/items/audit-sessions","""{"sharedOnly":true}""").also { assertEquals(HttpStatusCode.Created,it.status,it.bodyAsText()) }.bodyAsText().obj()
    private suspend fun ApplicationTestBuilder.session(token: String,tuntas: String,id: String) =
        get(token,tuntas,"/api/items/audit-sessions/$id").bodyAsText().obj()
    private suspend fun ApplicationTestBuilder.save(token:String,tuntas:String,id:String,revision:Int,checks:String,mutation:String=UUID.randomUUID().toString()) =
        post(token,tuntas,"/api/items/audit-sessions/$id/checks","""{"expectedRevision":$revision,"mutationId":"$mutation","checks":$checks}""")
    private suspend fun ApplicationTestBuilder.finish(token:String,tuntas:String,id:String,revision:Int) =
        post(token,tuntas,"/api/items/audit-sessions/$id/complete","""{"expectedRevision":$revision}""")

    @Test fun `batch validation rolls back earlier valid checks and revision`() = testApplication {
        configureFullApp()
        val (token,tuntas)=client.registerAndActivateTuntininkas()
        val a=item(token,tuntas); val b=item(token,tuntas,"Pjūklas"); val id=start(token,tuntas).id()
        val failed=save(token,tuntas,id,0,"""[{"itemId":"$a","result":"FOUND","actualQuantity":10},{"itemId":"$b","result":"FOUND","actualQuantity":-1}]""")
        assertEquals(HttpStatusCode.BadRequest,failed.status)
        val state=session(token,tuntas,id)
        assertEquals(0,state.int("revision")); assertTrue(state["checks"]!!.jsonArray.isEmpty())
        assertEquals(10,get(token,tuntas,"/api/items/$a").bodyAsText().obj().int("quantity"))
    }

    @Test fun `fixed list rejects later items and same mutation is idempotent while clear persists`() = testApplication {
        configureFullApp()
        val (token,tuntas)=client.registerAndActivateTuntininkas()
        val a=item(token,tuntas); val id=start(token,tuntas).id(); val later=item(token,tuntas,"Vėlesnis")
        assertEquals(1,session(token,tuntas,id)["items"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.BadRequest,save(token,tuntas,id,0,"""[{"itemId":"$later","result":"FOUND"}]""").status)
        val mutation=UUID.randomUUID().toString()
        repeat(2) { assertEquals(HttpStatusCode.OK,save(token,tuntas,id,0,"""[{"itemId":"$a","result":"FOUND","actualQuantity":10}]""",mutation).status) }
        assertEquals(1,session(token,tuntas,id).int("revision"))
        assertEquals(HttpStatusCode.BadRequest,save(token,tuntas,id,0,"""[{"itemId":"$a","result":"MISSING","actualQuantity":0}]""").status)
        val clear=post(token,tuntas,"/api/items/audit-sessions/$id/checks","""{"expectedRevision":1,"removeItemIds":["$a"]}""")
        assertEquals(HttpStatusCode.OK,clear.status,clear.bodyAsText())
        assertTrue(session(token,tuntas,id)["checks"]!!.jsonArray.isEmpty())
        assertEquals(HttpStatusCode.BadRequest,finish(token,tuntas,id,2).status)
    }

    @Test fun `stale stock blocks completion and recount requires a fresh check`() = testApplication {
        configureFullApp()
        val (token,tuntas)=client.registerAndActivateTuntininkas()
        val a=item(token,tuntas); val b=item(token,tuntas,"Puodas"); val id=start(token,tuntas).id()
        assertEquals(HttpStatusCode.OK,save(token,tuntas,id,0,"""[{"itemId":"$a","result":"FOUND","actualQuantity":8,"notes":"Du nerasti"},{"itemId":"$b","result":"FOUND","actualQuantity":10}]""").status)
        transaction { exec("UPDATE items SET quantity=12 WHERE id='$b'") }
        assertEquals(HttpStatusCode.BadRequest,finish(token,tuntas,id,1).status)
        assertEquals(10,get(token,tuntas,"/api/items/$a").bodyAsText().obj().int("quantity"))
        assertEquals("OPEN",session(token,tuntas,id)["status"]!!.jsonPrimitive.content)
        val recount=post(token,tuntas,"/api/items/audit-sessions/$id/items/$b/recount","""{"expectedRevision":1}""")
        assertEquals(HttpStatusCode.OK,recount.status,recount.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest,finish(token,tuntas,id,2).status)
        assertEquals(HttpStatusCode.OK,save(token,tuntas,id,2,"""[{"itemId":"$b","result":"FOUND","actualQuantity":12}]""").status)
        assertEquals(HttpStatusCode.OK,finish(token,tuntas,id,3).status)
        assertEquals(8,get(token,tuntas,"/api/items/$a").bodyAsText().obj().int("quantity"))
        assertEquals(12,get(token,tuntas,"/api/items/$b").bodyAsText().obj().int("quantity"))
    }

    @Test fun `outstanding loans are excluded from physical count and retained in total`() = testApplication {
        configureFullApp()
        val (token,tuntas)=client.registerAndActivateTuntininkas()
        val a=item(token,tuntas)
        transaction { exec("""INSERT INTO direct_item_loans(id,item_id,tuntas_id,issued_to_user_id,issued_by_user_id,quantity,returned_quantity,status,issued_at)
            SELECT gen_random_uuid(),'$a','$tuntas',created_by_user_id,created_by_user_id,4,1,'ACTIVE',NOW() FROM items WHERE id='$a'""") }
        val initial=start(token,tuntas); val id=initial.id()
        val entry=initial["items"]!!.jsonArray.single().jsonObject
        assertEquals(7,entry.int("expectedStorageQuantity")); assertEquals(3,entry.int("outstandingQuantity"))
        assertEquals(HttpStatusCode.OK,save(token,tuntas,id,0,"""[{"itemId":"$a","result":"FOUND","actualQuantity":6,"notes":"Trūksta vieno"}]""").status)
        val finished=finish(token,tuntas,id,1)
        assertEquals(HttpStatusCode.OK,finished.status,finished.bodyAsText())
        assertEquals(9,get(token,tuntas,"/api/items/$a").bodyAsText().obj().int("quantity"))
        assertEquals(9,finished.bodyAsText().obj()["checks"]!!.jsonArray.single().jsonObject.int("resultingTotalQuantity"))
    }

    @Test fun `zero stock can be audited again and missing explanation is required`() = testApplication {
        configureFullApp()
        val (token,tuntas)=client.registerAndActivateTuntininkas()
        val a=item(token,tuntas); val id=start(token,tuntas).id()
        assertEquals(HttpStatusCode.OK,save(token,tuntas,id,0,"""[{"itemId":"$a","result":"MISSING","actualQuantity":0}]""").status)
        assertEquals(HttpStatusCode.BadRequest,finish(token,tuntas,id,1).status)
        assertEquals(HttpStatusCode.OK,save(token,tuntas,id,1,"""[{"itemId":"$a","result":"MISSING","actualQuantity":0,"notes":"Nerastas"}]""").status)
        assertEquals(HttpStatusCode.OK,finish(token,tuntas,id,2).status)
        val next=start(token,tuntas).id()
        assertNotEquals(id,next)
        assertEquals(HttpStatusCode.OK,save(token,tuntas,next,0,"""[{"itemId":"$a","result":"FOUND","actualQuantity":2,"notes":"Rasti du"}]""").status)
        assertEquals(HttpStatusCode.OK,finish(token,tuntas,next,1).status)
        assertEquals(2,get(token,tuntas,"/api/items/$a").bodyAsText().obj().int("quantity"))
    }

    @Test fun `cancel preserves stock and old writes cannot bypass protocol`() = testApplication {
        configureFullApp()
        val (token,tuntas)=client.registerAndActivateTuntininkas()
        val a=item(token,tuntas); val id=start(token,tuntas).id()
        assertThrows(Exception::class.java) { transaction { exec("UPDATE item_check_sessions SET status='COMPLETED' WHERE id='$id'") } }
        assertEquals(HttpStatusCode.BadRequest,post(token,tuntas,"/api/items/audit-sessions/$id/checks","""{"checks":[{"itemId":"$a","result":"FOUND"}]}""").status)
        assertEquals(HttpStatusCode.OK,post(token,tuntas,"/api/items/audit-sessions/$id/cancel","""{"expectedRevision":0}""").status)
        assertEquals("CANCELLED",session(token,tuntas,id)["status"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest,finish(token,tuntas,id,1).status)
        assertEquals(10,get(token,tuntas,"/api/items/$a").bodyAsText().obj().int("quantity"))
    }

    @Test fun `viewer cannot create read modify or complete an audit`() = testApplication {
        configureFullApp()
        val (token,tuntas)=client.registerAndActivateTuntininkas()
        val a=item(token,tuntas); val id=start(token,tuntas).id()
        transaction { exec("DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE name='items.update')") }
        assertEquals(HttpStatusCode.Forbidden,get(token,tuntas,"/api/items/audit-sessions/$id").status)
        assertEquals(HttpStatusCode.Forbidden,post(token,tuntas,"/api/items/audit-sessions","{}" ).status)
        assertEquals(HttpStatusCode.Forbidden,save(token,tuntas,id,0,"""[{"itemId":"$a","result":"FOUND"}]""").status)
        assertEquals(HttpStatusCode.Forbidden,finish(token,tuntas,id,0).status)
    }

    @Test fun `loan change after counting invalidates stock even when total is unchanged`() = testApplication {
        configureFullApp()
        val (token,tuntas)=client.registerAndActivateTuntininkas()
        val a=item(token,tuntas); val id=start(token,tuntas).id()
        assertEquals(HttpStatusCode.OK,save(token,tuntas,id,0,"""[{"itemId":"$a","result":"FOUND","actualQuantity":10}]""").status)
        transaction { exec("""INSERT INTO direct_item_loans(id,item_id,tuntas_id,issued_to_user_id,issued_by_user_id,quantity,returned_quantity,status,issued_at)
            SELECT gen_random_uuid(),'$a','$tuntas',created_by_user_id,created_by_user_id,2,0,'ACTIVE',NOW() FROM items WHERE id='$a'""") }
        assertEquals(HttpStatusCode.BadRequest,finish(token,tuntas,id,1).status)
        val state=session(token,tuntas,id)
        assertNotEquals(JsonNull,state["items"]!!.jsonArray.single().jsonObject["conflict"])
    }

    @Test fun `completion updates kit membership and condition log together`() = testApplication {
        configureFullApp()
        val (token,tuntas)=client.registerAndActivateTuntininkas()
        val a=item(token,tuntas); val kit=UUID.randomUUID().toString()
        transaction {
            exec("""INSERT INTO inventory_kits(id,tuntas_id,name,status,created_at,updated_at) VALUES ('$kit','$tuntas','Komplektas','ACTIVE',NOW(),NOW())""")
            exec("""INSERT INTO inventory_kit_items(id,kit_id,item_id,quantity) VALUES (gen_random_uuid(),'$kit','$a',10)""")
        }
        val id=start(token,tuntas).id()
        assertEquals(HttpStatusCode.OK,save(token,tuntas,id,0,"""[{"itemId":"$a","result":"DAMAGED","actualQuantity":8,"conditionAtCheck":"DAMAGED","notes":"Du nerasti; kiti pažeisti"}]""").status)
        val completed=finish(token,tuntas,id,1)
        assertEquals(HttpStatusCode.OK,completed.status,completed.bodyAsText())
        transaction {
            exec("SELECT quantity FROM inventory_kit_items WHERE item_id='$a'") { it.next(); assertEquals(8,it.getInt(1)) }
            exec("SELECT COUNT(*) FROM item_condition_log WHERE item_id='$a' AND new_condition='DAMAGED'") { it.next(); assertEquals(1,it.getInt(1)) }
        }
    }


    @Test fun `event custody and reservation movements count physical units only once`() = testApplication {
        configureFullApp()
        val (token,tuntas)=client.registerAndActivateTuntininkas()
        val a=item(token,tuntas); val event=UUID.randomUUID(); val ei=UUID.randomUUID(); val root=UUID.randomUUID(); val group=UUID.randomUUID()
        transaction {
            exec("""INSERT INTO events(id,tuntas_id,name,type,start_date,end_date,created_at,updated_at) VALUES ('$event','$tuntas','Stovykla','CAMP',CURRENT_DATE,CURRENT_DATE,NOW(),NOW())""")
            exec("""INSERT INTO event_inventory_items(id,event_id,item_id,name,planned_quantity,available_quantity,created_at) VALUES ('$ei','$event','$a','Kirviai',10,10,NOW())""")
            exec("""INSERT INTO event_inventory_custody(id,event_inventory_item_id,quantity,returned_quantity,status,created_by_user_id,created_at)
                SELECT '$root','$ei',3,0,'OPEN',created_by_user_id,NOW() FROM items WHERE id='$a'""")
            exec("""INSERT INTO event_inventory_custody(id,event_inventory_item_id,parent_custody_id,quantity,returned_quantity,status,created_by_user_id,created_at)
                SELECT gen_random_uuid(),'$ei','$root',1,0,'OPEN',created_by_user_id,NOW() FROM items WHERE id='$a'""")
        }
        val first=start(token,tuntas)
        assertEquals(3,first["items"]!!.jsonArray.single().jsonObject.int("outstandingQuantity"))
        val id=first.id()
        assertEquals(HttpStatusCode.OK,post(token,tuntas,"/api/items/audit-sessions/$id/cancel","""{"expectedRevision":0}""").status)
        transaction {
            exec("UPDATE event_inventory_items SET reservation_group_id='$group' WHERE id='$ei'")
            exec("""INSERT INTO reservation_movements(id,reservation_group_id,item_id,type,quantity,performed_by_user_id,created_at)
                SELECT gen_random_uuid(),'$group','$a','ISSUE',4,created_by_user_id,NOW() FROM items WHERE id='$a'""")
            exec("""INSERT INTO reservation_movements(id,reservation_group_id,item_id,type,quantity,performed_by_user_id,created_at)
                SELECT gen_random_uuid(),'$group','$a','RETURN_MARKED',1,created_by_user_id,NOW() FROM items WHERE id='$a'""")
        }
        val second=start(token,tuntas)
        val entry=second["items"]!!.jsonArray.single().jsonObject
        assertEquals(4,entry.int("outstandingQuantity"))
        assertEquals(6,entry.int("expectedStorageQuantity"))
        transaction { exec("""INSERT INTO reservation_movements(id,reservation_group_id,item_id,type,quantity,performed_by_user_id,created_at)
            SELECT gen_random_uuid(),'$group','$a','RETURN',1,created_by_user_id,NOW() FROM items WHERE id='$a'""") }
        assertNotEquals(JsonNull,session(token,tuntas,second.id())["items"]!!.jsonArray.single().jsonObject["conflict"])
    }


    @Test fun `two simultaneous writes cannot both use the same revision`() = testApplication {
        configureFullApp()
        val (token,tuntas)=client.registerAndActivateTuntininkas()
        val a=item(token,tuntas); val session=start(token,tuntas); val id=session.id()
        val user=UUID.fromString(session["startedByUserId"]!!.jsonPrimitive.content)
        val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
        val ready=java.util.concurrent.CountDownLatch(2)
        val go=java.util.concurrent.CountDownLatch(1)
        try {
            val attempts=(1..2).map { count -> executor.submit(java.util.concurrent.Callable {
                ready.countDown(); go.await()
                lt.skautai.services.ItemCheckService().upsertStorageAuditChecks(UUID.fromString(id),UUID.fromString(tuntas),user,
                    lt.skautai.models.requests.UpsertStorageAuditChecksRequest(
                        checks=listOf(lt.skautai.models.requests.UpsertStorageAuditCheckRequest(a,"FOUND",actualQuantity=count)),
                        expectedRevision=0,mutationId=UUID.randomUUID().toString()))
            }) }
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)); go.countDown()
            val outcomes=attempts.map { it.get(20,java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1,outcomes.count { it.isSuccess })
            assertEquals(1,outcomes.count { it.isFailure })
            val after=session(token,tuntas,id)
            assertEquals(1,after.int("revision")); assertEquals(1,after["checks"]!!.jsonArray.size)
        } finally { go.countDown(); executor.shutdownNow() }
    }

    @Test fun `legacy session is readable but requires restart and supports cancellation`() = testApplication {
        configureFullApp()
        val (token,tuntas)=client.registerAndActivateTuntininkas()
        val a=item(token,tuntas); val id=UUID.randomUUID().toString()
        transaction { exec("""INSERT INTO item_check_sessions(id,tuntas_id,context_type,started_by_user_id,status,scope_item_count,created_at)
            SELECT '$id','$tuntas','STORAGE_AUDIT',created_by_user_id,'OPEN',1,NOW() FROM items WHERE id='$a'""") }
        val old=session(token,tuntas,id)
        assertEquals("true",old["requiresRestart"]!!.jsonPrimitive.content)
        assertEquals("false",old["canEdit"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest,finish(token,tuntas,id,0).status)
        assertEquals(HttpStatusCode.OK,post(token,tuntas,"/api/items/audit-sessions/$id/cancel","""{"expectedRevision":0}""").status)
    }

    @Test fun `unit leader scope excludes shared other unit and transferred inventory`() = testApplication {
        configureFullApp()
        val (token,tuntas)=client.registerAndActivateTuntininkas()
        val unit=post(token,tuntas,"/api/organizational-units","""{"name":"Skautai","type":"SKAUTU_DRAUGOVE"}""").bodyAsText().obj().id()
        val other=post(token,tuntas,"/api/organizational-units","""{"name":"Kiti","type":"SKAUTU_DRAUGOVE"}""").bodyAsText().obj().id()
        val role=TestHelper.getRoleId(tuntas,"Draugininkas")
        val invite=post(token,tuntas,"/api/invitations","""{"roleId":"$role","organizationalUnitId":"$unit","expiresInHours":48}""").bodyAsText().obj()["code"]!!.jsonPrimitive.content
        val registration=post(token,tuntas,"/api/auth/register/invite","""{"name":"Vadovas","surname":"Testas","email":"audit-scope@test.com","password":"testas123","inviteCode":"$invite"}""").bodyAsText().obj()
        val scopedToken=registration["token"]!!.jsonPrimitive.content
        val own=item(token,tuntas,"Savas"); val foreign=item(token,tuntas,"Kitas"); val shared=item(token,tuntas,"Bendras"); val transferred=item(token,tuntas,"Perduotas")
        transaction {
            exec("UPDATE items SET custodian_id='$unit', origin='UNIT_ACQUIRED' WHERE id='$own'")
            exec("UPDATE items SET custodian_id='$other', origin='UNIT_ACQUIRED' WHERE id='$foreign'")
            exec("UPDATE items SET custodian_id='$unit', origin='TRANSFERRED_FROM_TUNTAS' WHERE id='$transferred'")
        }
        val scoped=post(scopedToken,tuntas,"/api/items/audit-sessions","{}").also { assertEquals(HttpStatusCode.Created,it.status,it.bodyAsText()) }.bodyAsText().obj()
        assertEquals(listOf(own),scoped["items"]!!.jsonArray.map { it.jsonObject["item"]!!.jsonObject.id() })
        val global=start(token,tuntas).id()
        assertEquals(HttpStatusCode.NotFound,get(scopedToken,tuntas,"/api/items/audit-sessions/$global").status)
        assertEquals(HttpStatusCode.BadRequest,finish(scopedToken,tuntas,global,0).status)
        assertEquals(HttpStatusCode.BadRequest,save(scopedToken,tuntas,scoped.id(),0,"""[{"itemId":"$shared","result":"FOUND"}]""").status)
    }

}
