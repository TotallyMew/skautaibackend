package lt.skautai.services

import lt.skautai.database.tables.Items
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object ItemScopeHelper {

    fun getItemCustodianId(itemId: UUID, tuntasId: UUID): UUID? {
        return transaction {
            Items.selectAll()
                .where { (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }
                .firstOrNull()
                ?.get(Items.custodianId)
        }
    }
}