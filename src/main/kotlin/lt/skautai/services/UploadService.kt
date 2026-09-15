package lt.skautai.services

import kotlinx.datetime.Clock
import lt.skautai.database.tables.*
import lt.skautai.plugins.isActiveTenantMember
import lt.skautai.util.UploadStorage
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.time.Duration.Companion.hours

class UploadRejected(val status: Int, message: String) : IllegalArgumentException(message)

/** File ownership is immutable. Existing object columns are the authoritative references. */
object UploadService {
    fun downloadName(url: String): String {
        val name = url.substringAfterLast('/')
        if ('.' in name) return name
        val extension = when (contentType(url)) {
            "application/pdf" -> ".pdf"
            "image/png" -> ".png"
            "image/jpeg" -> ".jpg"
            "image/webp" -> ".webp"
            else -> ""
        }
        return name + extension
    }
    data class Ticket(val id: UUID, val url: String)
    private fun limit(name: String, fallback: Long): Long =
        (System.getProperty(name) ?: System.getenv(name))?.toLongOrNull()?.takeIf { it > 0 } ?: fallback

    fun reserve(userId: UUID, tuntasId: UUID, kind: String, maxBytes: Long): Ticket = transaction {
        // Global user budget and tenant budget use the same lock order in both deployments.
        Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
            ?: throw UploadRejected(403, "Active tuntas membership required")
        Tuntai.selectAll().where { Tuntai.id eq tuntasId }.forUpdate().firstOrNull()
            ?: throw UploadRejected(403, "Active tuntas membership required")
        if (!isActiveTenantMember(userId, tuntasId) ||
            !PermissionContextService.resolve(userId, tuntasId).has("items.view")) {
            throw UploadRejected(403, "Active inventory access required")
        }
        val tenantRows = StoredUploads.selectAll().where {
            (StoredUploads.tuntasId eq tuntasId) and (StoredUploads.state neq "DELETED")
        }.toList()
        val userRows = StoredUploads.selectAll().where {
            (StoredUploads.uploaderId eq userId) and (StoredUploads.state neq "DELETED")
        }.toList()
        if (tenantRows.sumOf { it[StoredUploads.byteSize] } > limit("UPLOAD_TENANT_BYTES", 2L * 1024 * 1024 * 1024) - maxBytes ||
            userRows.sumOf { it[StoredUploads.byteSize] } > limit("UPLOAD_USER_BYTES", 256L * 1024 * 1024) - maxBytes ||
            tenantRows.size >= limit("UPLOAD_TENANT_FILES", 10_000) ||
            userRows.size >= limit("UPLOAD_USER_FILES", 2_000) ||
            userRows.count { it[StoredUploads.state] == "RECEIVING" } >= limit("UPLOAD_USER_CONCURRENT", 3)) {
            throw UploadRejected(429, "Upload storage or concurrency limit reached")
        }
        val id = UUID.randomUUID()
        val url = (if (kind == "IMAGE") UploadStorage.imageUrlPrefix else UploadStorage.documentUrlPrefix) + "/$id"
        StoredUploads.insert {
            it[StoredUploads.id] = id; it[StoredUploads.tuntasId] = tuntasId
            it[uploaderId] = userId; it[fileUrl] = url; it[StoredUploads.kind] = kind
            it[contentType] = "application/octet-stream"; it[byteSize] = maxBytes
            it[state] = "RECEIVING"; it[createdAt] = Clock.System.now()
        }
        Ticket(id, url)
    }

    fun stagingFile(ticket: Ticket): File = File(UploadStorage.rootDir(), "staging").let {
        it.mkdirs(); File(it, ticket.id.toString())
    }

    fun finish(ticket: Ticket, staged: File, contentType: String) = transaction {
        val row = StoredUploads.selectAll().where { StoredUploads.id eq ticket.id }.forUpdate().first()
        check(row[StoredUploads.state] == "RECEIVING")
        val actor = row[StoredUploads.uploaderId]
        if (actor == null || !isActiveTenantMember(actor, row[StoredUploads.tuntasId]) ||
            !PermissionContextService.resolve(actor, row[StoredUploads.tuntasId]).has("items.view")) {
            throw UploadRejected(403, "Active inventory access required")
        }
        val destination = fileFor(row[StoredUploads.fileUrl]) ?: error("Invalid generated upload path")
        destination.parentFile.mkdirs()
        val size = staged.length()
        check(size <= row[StoredUploads.byteSize])
        Files.move(staged.toPath(), destination.toPath())
        StoredUploads.update({ StoredUploads.id eq ticket.id }) {
            it[byteSize] = size; it[StoredUploads.contentType] = contentType; it[state] = "READY"
        }
    }

    fun abandon(ticket: Ticket) {
        // Mark first; all attachments require READY and hold this row lock.
        val mayDelete = transaction {
            val row = StoredUploads.selectAll().where { StoredUploads.id eq ticket.id }.forUpdate().firstOrNull()
            if (row == null || row[StoredUploads.state] != "RECEIVING") false else {
                StoredUploads.update({ StoredUploads.id eq ticket.id }) { it[state] = "DELETING" }; true
            }
        }
        if (mayDelete) eraseClaimed(ticket)
    }

    /** Call within the owning object's SQL transaction, before its first write. */
    fun authorizeBinding(url: String?, tenant: UUID, actor: UUID?, kind: String): Exception? {
        if (url.isNullOrBlank()) return null
        val row = StoredUploads.selectAll().where { StoredUploads.fileUrl eq url }.forUpdate().firstOrNull()
            ?: return IllegalArgumentException("Select a newly uploaded file; unknown or legacy paths cannot be attached")
        if (row[StoredUploads.tuntasId] != tenant || row[StoredUploads.kind] != kind ||
            row[StoredUploads.state] != "READY" || actor == null || !isActiveTenantMember(actor, tenant)) {
            return IllegalArgumentException("Upload not available")
        }
        // Once attached, current object visibility governs reuse; stale uploader authority is insufficient.
        val allowed = if (row[StoredUploads.attachedAt] == null) row[StoredUploads.uploaderId] == actor
            else kind == "IMAGE" && visibleImageReference(url, tenant, actor)
        if (!allowed) return IllegalArgumentException("Upload not available")
        StoredUploads.update({ StoredUploads.id eq row[StoredUploads.id] }) { it[attachedAt] = Clock.System.now() }
        return null
    }

    fun canReadImage(url: String, tenant: UUID, actor: UUID): Boolean = transaction {
        if (!isActiveTenantMember(actor, tenant)) return@transaction false
        val row = StoredUploads.selectAll().where { StoredUploads.fileUrl eq url }.firstOrNull()
        if (row != null) {
            if (row[StoredUploads.tuntasId] != tenant || row[StoredUploads.state] != "READY") return@transaction false
            if (row[StoredUploads.attachedAt] == null) return@transaction row[StoredUploads.uploaderId] == actor
        } else {
            // Never infer ownership from a supplied URL; only unambiguous legacy object references.
            val tenants = Items.select(Items.tuntasId).where { Items.photoUrl eq url }.map { it[Items.tuntasId] }.toSet()
            if (tenants != setOf(tenant)) return@transaction false
        }
        visibleImageReference(url, tenant, actor)
    }

    private fun visibleImageReference(url: String, tenant: UUID, actor: UUID): Boolean {
        val permissions = PermissionContextService.resolve(actor, tenant)
        return Items.selectAll().where { (Items.photoUrl eq url) and (Items.tuntasId eq tenant) }
            .any { row ->
                permissions.targetAllowed("items.view", row[Items.custodianId]) &&
                    ItemService().getItem(row[Items.id], tenant, actor).isSuccess
            }
    }

    /** Invoice endpoints already authorize the purchase; additionally reject foreign/ambiguous files. */
    fun documentBelongsTo(url: String, tenant: UUID): Boolean = transaction {
        val row = StoredUploads.selectAll().where { StoredUploads.fileUrl eq url }.firstOrNull()
        if (row != null) return@transaction row[StoredUploads.tuntasId] == tenant &&
            row[StoredUploads.kind] == "DOCUMENT" && row[StoredUploads.state] == "READY"
        val tenants = EventPurchases.innerJoin(Events, { EventPurchases.eventId }, { Events.id })
            .select(Events.tuntasId).where { EventPurchases.invoiceFileUrl eq url }.map { it[Events.tuntasId] } +
            EventPurchaseInvoices.innerJoin(EventPurchases, { EventPurchaseInvoices.purchaseId }, { EventPurchases.id })
                .innerJoin(Events, { EventPurchases.eventId }, { Events.id })
                .select(Events.tuntasId).where { EventPurchaseInvoices.fileUrl eq url }.map { it[Events.tuntasId] }
        tenants.toSet() == setOf(tenant)
    }

    fun contentType(url: String): String? = transaction {
        StoredUploads.select(StoredUploads.contentType).where { StoredUploads.fileUrl eq url }.firstOrNull()?.get(StoredUploads.contentType)
    }

    private fun hasReferences(url: String): Boolean =
        Items.selectAll().where { Items.photoUrl eq url }.any() ||
        ItemAttachments.selectAll().where { ItemAttachments.fileUrl eq url }.any() ||
        EventPurchases.selectAll().where { EventPurchases.invoiceFileUrl eq url }.any() ||
        EventPurchaseInvoices.selectAll().where { EventPurchaseInvoices.fileUrl eq url }.any()

    /** Only new never-attached uploads expire. Historical/referenced files are never auto-purged. */
    fun cleanupUnattached(): Int {
        val cutoff = Clock.System.now() - limit("UPLOAD_UNATTACHED_HOURS", 24).hours
        val claimed = transaction {
            StoredUploads.selectAll().where {
                ((StoredUploads.state inList listOf("RECEIVING", "READY")) and
                    StoredUploads.attachedAt.isNull() and (StoredUploads.createdAt less cutoff)) or
                    (StoredUploads.state eq "DELETING")
            }.orderBy(StoredUploads.id).limit(100).forUpdate().toList().mapNotNull { row ->
                if (hasReferences(row[StoredUploads.fileUrl])) null else {
                    StoredUploads.update({ StoredUploads.id eq row[StoredUploads.id] }) { it[state] = "DELETING" }
                    Ticket(row[StoredUploads.id], row[StoredUploads.fileUrl])
                }
            }
        }
        claimed.forEach(::eraseClaimed)
        return claimed.size
    }

    private fun eraseClaimed(ticket: Ticket) {
        // A DELETING row cannot be attached; deletion retries are idempotent across workers.
        val stored = fileFor(ticket.url) ?: return
        val staged = stagingFile(ticket)
        Files.deleteIfExists(staged.toPath())
        Files.deleteIfExists(stored.toPath())
        transaction { StoredUploads.update({ (StoredUploads.id eq ticket.id) and (StoredUploads.state eq "DELETING") }) {
            it[state] = "DELETED"; it[byteSize] = 0
        } }
    }

    private fun fileFor(url: String): File? = when {
        url.startsWith(UploadStorage.imageUrlPrefix + "/") -> UploadStorage.resolveImage(url.substringAfterLast('/'))
        url.startsWith(UploadStorage.documentUrlPrefix + "/") -> UploadStorage.resolveDocument(url.substringAfterLast('/'))
        else -> null
    }
}
