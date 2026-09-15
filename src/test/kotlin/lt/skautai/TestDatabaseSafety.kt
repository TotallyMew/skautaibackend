package lt.skautai

import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.net.URI

/** Never allow fixtures to reset an unmarked application database. */
object TestDatabaseSafety {
    fun validateTarget(url: String, user: String, optIn: String?) {
        val uri = URI(url.removePrefix("jdbc:"))
        require(uri.scheme == "postgresql" && uri.host in setOf("localhost", "127.0.0.1", "::1")) {
            "Destructive tests require a local disposable PostgreSQL database"
        }
        require(uri.path.matches(Regex("/[_a-zA-Z0-9]+_test")) && user == "skautai_test") {
            "Destructive tests require a *_test database and dedicated skautai_test role"
        }
        require(optIn == "disposable") { "Set TEST_DB_ALLOW_RESET=disposable for the marked test database" }
    }

    fun requireMarkedConnection() {
        val safe = TransactionManager.current().exec(
            "SELECT current_user = 'skautai_test' AND shobj_description(oid, 'pg_database') = 'skautai-disposable-test-database' AS safe FROM pg_database WHERE datname = current_database()"
        ) { rows -> rows.next() && rows.getBoolean("safe") } ?: false
        check(safe) { "Refusing destructive fixtures: connection is not a marked disposable test database" }
    }
}
