package lt.skautai.services

import lt.skautai.database.tables.Items
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object ItemScopeHelper {

    fun getItemOrgUnitId(itemId: UUID, tuntasId: UUID): UUID? {
        return transaction {
            val item = Items.selectAll()
                .where { (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }
                .firstOrNull() ?: return@transaction null

            if (item[Items.ownerType] == "DRAUGOVE") {
                item[Items.ownerId]
            } else {
                null
            }
        }
    }
}