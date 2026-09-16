package lt.skautai

import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.Users
import lt.skautai.services.ItemService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.statements.StatementContext
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemPaginationPerformanceTest {
    @BeforeAll fun setup() = TestHelper.setupDatabase()
    @AfterAll fun teardown() = TestHelper.teardownDatabase()
    @BeforeEach fun clean() = TestHelper.cleanTables()

    @Test fun `page query count stays constant as page size grows and pages do not overlap`() = testApplication {
        configureFullApp()
        val (_, tenant) = client.registerAndActivateTuntininkas(email = "paging@test.com")
        val tuntasId = UUID.fromString(tenant)
        val userId = transaction { Users.selectAll().where { Users.email eq "paging@test.com" }.single()[Users.id] }
        transaction {
            repeat(75) { index ->
                Items.insert {
                    it[Items.tuntasId] = tuntasId
                    it[name] = "Daiktas " + index.toString().padStart(3, '0')
                    it[type] = "COLLECTIVE"; it[category] = "TOOLS"
                    it[quantity] = 1; it[createdByUserId] = userId
                    it[qrToken] = UUID.randomUUID().toString()
                    it[createdAt] = Clock.System.now(); it[updatedAt] = Clock.System.now()
                }
            }
        }
        fun page(limit: Int, offset: Int = 0): Pair<lt.skautai.models.responses.ItemListResponse, Int> = transaction {
            var queries = 0
            addLogger(object : SqlLogger {
                override fun log(context: StatementContext, transaction: Transaction) { queries++ }
            })
            val page = ItemService().getItems(tuntasId, userId, status = "ACTIVE", limit = limit, offset = offset).getOrThrow()
            page to queries
        }
        val (small, smallQueries) = page(1)
        val (first, firstQueries) = page(30)
        val (second, _) = page(30, 30)
        val (last, _) = page(30, 60)
        assertEquals(smallQueries, firstQueries, "Empty optional data must not trigger queries per item")
        assertEquals(75, first.total)
        assertEquals(30, first.items.size)
        assertEquals(30, second.items.size)
        assertEquals(15, last.items.size)
        assertTrue(first.hasMore); assertTrue(second.hasMore); assertFalse(last.hasMore)
        assertEquals(75, (first.items + second.items + last.items).map { it.id }.distinct().size)
        assertEquals(small.items.single().id, first.items.first().id)
        assertTrue(first.items.all { it.customFields.isEmpty() && it.quantityBreakdown.isEmpty() && it.kitId == null })
        println("Inventory SELECT statements: page 1=$smallQueries; page 30=$firstQueries")
    }
}

