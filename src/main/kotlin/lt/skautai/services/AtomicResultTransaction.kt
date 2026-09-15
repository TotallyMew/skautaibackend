package lt.skautai.services

import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/** A rejected domain operation must abort its writes, just like a database failure. */
internal fun <T> atomicResultTransaction(statement: Transaction.() -> Result<T>): Result<T> = try {
    Result.success(transaction {
        statement().getOrElse { throw RejectedDomainTransaction(it) }
    })
} catch (rejected: RejectedDomainTransaction) {
    Result.failure(rejected.failure)
}

private class RejectedDomainTransaction(val failure: Throwable) : RuntimeException(failure)
