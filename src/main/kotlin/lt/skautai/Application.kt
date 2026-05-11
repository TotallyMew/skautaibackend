package lt.skautai

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import lt.skautai.plugins.configureRouting
import lt.skautai.plugins.configureSecurity
import lt.skautai.plugins.configureSerialization
import lt.skautai.database.tables.Tuntai
import lt.skautai.models.responses.ErrorResponse
import lt.skautai.services.PermissionSeeder
import lt.skautai.services.VadovasRankSupport
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureDatabases()
    configureSerialization()
    configureSecurity()
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
    }
    install(StatusPages) {
        exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }
    configureRouting()
    PermissionSeeder.seedPermissions()
    transaction {
        Tuntai.selectAll().map { it[Tuntai.id] }.forEach { tuntasId ->
            PermissionSeeder.seedRolePermissions(tuntasId)
        }
    }
    VadovasRankSupport.backfillExistingLeadershipUsers()
}

fun Application.configureDatabases() {
    val config = environment.config
    val url = config.property("database.url").getString()
    val driver = config.property("database.driver").getString()
    val user = config.property("database.user").getString()
    val password = config.property("database.password").getString()

    Database.connect(
        url = url,
        driver = driver,
        user = user,
        password = password
    )

    val logger = log
    transaction {
        exec("SELECT 1")
        exec("ALTER TABLE items ADD COLUMN IF NOT EXISTS qr_token VARCHAR(36)")
        exec("ALTER TABLE items DROP CONSTRAINT IF EXISTS items_category_check")
        exec("ALTER TABLE items DROP CONSTRAINT IF EXISTS items_condition_check")
        exec("ALTER TABLE items ALTER COLUMN condition TYPE VARCHAR(30)")
        exec("ALTER TABLE IF EXISTS item_condition_log ALTER COLUMN previous_condition TYPE VARCHAR(30)")
        exec("ALTER TABLE IF EXISTS item_condition_log ALTER COLUMN new_condition TYPE VARCHAR(30)")
        exec("UPDATE items SET qr_token = uuid_generate_v4()::text WHERE qr_token IS NULL")
        exec("ALTER TABLE items ALTER COLUMN qr_token SET NOT NULL")
        exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_items_qr_token ON items(qr_token)")
        exec("ALTER TABLE IF EXISTS draugove_requisitions DROP CONSTRAINT IF EXISTS draugove_requisitions_status_check")
        exec("ALTER TABLE IF EXISTS draugove_requisitions DROP CONSTRAINT IF EXISTS draugove_requisitions_unit_review_status_check")
        exec("ALTER TABLE IF EXISTS draugove_requisitions DROP CONSTRAINT IF EXISTS draugove_requisitions_top_level_review_status_check")
        exec("ALTER TABLE IF EXISTS draugove_requisitions ADD COLUMN IF NOT EXISTS purchased_at TIMESTAMP")
        exec("ALTER TABLE IF EXISTS draugove_requisitions ADD COLUMN IF NOT EXISTS purchased_by_user_id UUID REFERENCES users(id)")
        exec("ALTER TABLE IF EXISTS draugove_requisitions ADD COLUMN IF NOT EXISTS added_to_inventory_at TIMESTAMP")
        exec("ALTER TABLE IF EXISTS draugove_requisitions ADD COLUMN IF NOT EXISTS added_to_inventory_by_user_id UUID REFERENCES users(id)")
        exec(
            """
            ALTER TABLE IF EXISTS draugove_requisitions
            ADD CONSTRAINT draugove_requisitions_status_check
            CHECK (status IN ('DRAFT', 'SUBMITTED', 'PARTIALLY_APPROVED', 'APPROVED', 'PURCHASED', 'INVENTORY_ADDED', 'REJECTED', 'CANCELLED'))
            """.trimIndent()
        )
        exec(
            """
            ALTER TABLE IF EXISTS draugove_requisitions
            ADD CONSTRAINT draugove_requisitions_unit_review_status_check
            CHECK (unit_review_status IN ('PENDING', 'APPROVED', 'FORWARDED', 'REJECTED', 'SKIPPED', 'CANCELLED'))
            """.trimIndent()
        )
        exec(
            """
            ALTER TABLE IF EXISTS draugove_requisitions
            ADD CONSTRAINT draugove_requisitions_top_level_review_status_check
            CHECK (top_level_review_status IN ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
            """.trimIndent()
        )
        exec(
            """
            CREATE TABLE IF NOT EXISTS item_history (
                id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                event_type VARCHAR(40) NOT NULL,
                quantity_change INTEGER,
                performed_by_user_id UUID REFERENCES users(id),
                requisition_id UUID REFERENCES draugove_requisitions(id),
                notes TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        exec(
            """
            CREATE TABLE IF NOT EXISTS event_purchase_item_reconciliations (
                id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                purchase_item_id UUID NOT NULL REFERENCES event_purchase_items(id) ON DELETE CASCADE,
                decision VARCHAR(40) NOT NULL CHECK (decision IN ('ADD_NEW_ITEM', 'INCREASE_EXISTING_ITEM', 'CONSUMED', 'IGNORE')),
                quantity INTEGER NOT NULL CHECK (quantity > 0),
                added_inventory_item_id UUID REFERENCES items(id),
                performed_by_user_id UUID NOT NULL REFERENCES users(id),
                notes TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        exec("CREATE INDEX IF NOT EXISTS idx_event_purchase_item_reconciliations_item ON event_purchase_item_reconciliations(purchase_item_id)")
        exec(
            """
            CREATE TABLE IF NOT EXISTS item_custom_fields (
                id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                field_name VARCHAR(100) NOT NULL,
                field_value TEXT
            )
            """.trimIndent()
        )
        logger.info("Database connected successfully")
    }
}
