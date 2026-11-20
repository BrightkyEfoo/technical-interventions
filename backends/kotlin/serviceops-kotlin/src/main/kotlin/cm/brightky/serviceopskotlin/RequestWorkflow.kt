package cm.brightky.serviceopskotlin

import cm.brightky.accounts.KeycloakAccounts
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID
import javax.imageio.ImageIO

@Service
@Transactional
class RequestWorkflow(
    private val requests: Requests, private val jdbc: JdbcTemplate, private val json: ObjectMapper,
    private val accounts: KeycloakAccounts,
) {
    fun create(actor: Account, title: String, priority: Priority, key: String?): Intervention {
        if (actor.role !in setOf(Role.REQUESTER, Role.MANAGER)) fail(Fault.REQUEST_FORBIDDEN, "This role cannot create requests")
        val fingerprint = json.writeValueAsString(listOf(title.trim { Character.isWhitespace(it) }, priority))
        return replay("${actor.id}/create", key, fingerprint) {
            persist(Intervention.open(title, actor.id, priority), actor, "CREATE")
        }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID, actor: Account): Intervention = visible(id, actor)

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(actor: Account, query: String, status: Status?, page: Int, size: Int): RequestPage {
        if (page !in 0..100_000 || size !in 1..100 || query.length > 120) {
            fail(Fault.INVALID_INPUT, "Invalid pagination or search filter")
        }
        return requests.search(actor, query.trim(), status, page, size)
    }

    fun assign(id: UUID, actor: Account, technologyId: UUID, version: Long) {
        val request = visible(id, actor).withVersion(version)
        if (actor.role != Role.MANAGER) fail(Fault.ASSIGNMENT_FORBIDDEN, "Only a manager can assign a request")
        val candidate = if (accounts.isConfigured) accounts.technician(technologyId)
            .map { Account(it.id(), Role.TECHNICIAN) }.orElse(null) else demoAccounts[technologyId]
        val recipient = candidate ?: fail(Fault.USER_NOT_FOUND, "Technician not found: $technologyId")
        val saved = persist(request.assign(actor, recipient), actor, "ASSIGN")
        jdbc.update("INSERT INTO request_outbox(id, request_id, request_version, recipient_id) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(), saved.id, saved.version, recipient.id)
    }

    fun start(id: UUID, actor: Account, version: Long) = persist(visible(id, actor).withVersion(version).start(actor), actor, "START")

    fun resolve(id: UUID, actor: Account, version: Long, report: String, key: String?): Intervention {
        visible(id, actor)
        val fingerprint = json.writeValueAsString(listOf(version, report.trim { Character.isWhitespace(it) }))
        return replay("${actor.id}/resolve/$id", key, fingerprint) {
            persist(visible(id, actor).withVersion(version).resolve(actor, report), actor, "RESOLVE")
        }
    }

    fun close(id: UUID, actor: Account, version: Long) = persist(visible(id, actor).withVersion(version).close(actor), actor, "CLOSE")

    @Transactional(readOnly = true)
    fun comments(id: UUID, actor: Account): List<Map<String, Any?>> {
        visible(id, actor)
        return jdbc.queryForList("""
            SELECT id, author_id AS "authorId", body, created_at AS "createdAt"
            FROM request_comments WHERE request_id = ? ORDER BY created_at, id
        """, id)
    }

    fun comment(id: UUID, actor: Account, version: Long, body: String): Map<String, Any?> {
        val request = editable(id, actor, version)
        val text = body.trim { Character.isWhitespace(it) }
        if (text.length !in 1..2000) fail(Fault.INVALID_INPUT, "Comment must have 1 to 2000 characters")
        persist(request, actor, "COMMENT")
        return jdbc.queryForMap("""
            INSERT INTO request_comments(id, request_id, author_id, body) VALUES (?, ?, ?, ?)
            RETURNING id, author_id AS "authorId", body, created_at AS "createdAt"
        """, UUID.randomUUID(), id, actor.id, text)
    }

    fun attach(id: UUID, actor: Account, version: Long, bytes: ByteArray): UUID {
        val request = editable(id, actor, version)
        val type = mediaType(bytes)
        persist(request, actor, "ATTACH")
        val attachmentId = UUID.randomUUID()
        jdbc.update("INSERT INTO request_attachments(id, request_id, media_type, content) VALUES (?, ?, ?, ?)",
            attachmentId, id, type, bytes)
        return attachmentId
    }

    @Transactional(readOnly = true)
    fun download(id: UUID, fileId: UUID, actor: Account): Pair<String, ByteArray> {
        visible(id, actor)
        val result = jdbc.query("SELECT media_type, content FROM request_attachments WHERE request_id = ? AND id = ?",
            { row, _ -> row.getString(1) to row.getBytes(2) }, id, fileId)
        return result.firstOrNull() ?: fail(Fault.REQUEST_NOT_FOUND, "Attachment not found")
    }

    private fun visible(id: UUID, actor: Account) = requests.find(id)?.takeIf { it.visibleTo(actor) }
        ?: fail(Fault.REQUEST_NOT_FOUND, "Request not found: $id")

    @Transactional(readOnly = true)
    fun history(id: UUID, actor: Account): List<HistoryEntry> {
        visible(id, actor)
        return jdbc.query("""
            SELECT version, actor_id, action, status, occurred_at
            FROM request_history WHERE request_id = ? ORDER BY version
        """, { row, _ -> HistoryEntry(row.getLong(1), row.getObject(2, UUID::class.java), row.getString(3),
            Status.valueOf(row.getString(4)), row.getObject(5, java.time.OffsetDateTime::class.java).toInstant()) }, id)
    }

    private fun persist(request: Intervention, actor: Account, action: String): Intervention =
        requests.save(request).also {
            jdbc.update("INSERT INTO request_history(request_id, version, actor_id, action, status) VALUES (?, ?, ?, ?, ?)",
                it.id, it.version, actor.id, action, it.status.name)
        }

    private fun editable(id: UUID, actor: Account, version: Long): Intervention =
        visible(id, actor).withVersion(version).also {
            if (it.status == Status.CLOSED) fail(Fault.REQUEST_STATE_CONFLICT, "This request is closed")
        }

    private fun replay(scope: String, key: String?, fingerprint: String, work: () -> Intervention): Intervention {
        if (key.isNullOrBlank() || key.length > 128) {
            fail(Fault.INVALID_INPUT, "Idempotency-Key is required and must be at most 128 characters")
        }
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "$scope/$key")
        val receipt = jdbc.queryForList(
            "SELECT fingerprint, response::text AS response FROM request_receipts WHERE scope = ? AND command_key = ?",
            scope, key
        ).firstOrNull()
        return if (receipt == null) {
            work().also {
                jdbc.update("INSERT INTO request_receipts(scope, command_key, fingerprint, response) VALUES (?, ?, ?, CAST(? AS jsonb))",
                    scope, key, fingerprint, json.writeValueAsString(it))
            }
        } else {
            if (receipt["fingerprint"] != fingerprint)
                fail(Fault.IDEMPOTENCY_CONFLICT, "This key is already used with a different command")
            json.readValue(receipt["response"] as String, Intervention::class.java)
        }
    }

    private fun mediaType(bytes: ByteArray): String {
        if (bytes.size > 5 * 1024 * 1024) fail(Fault.ATTACHMENT_TOO_LARGE, "Image exceeds 5 MiB")
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { source ->
                val readers = ImageIO.getImageReaders(source)
                if (readers.hasNext()) {
                    val reader = readers.next()
                    try {
                        reader.setInput(source)
                        if (reader.getWidth(0).toLong() * reader.getHeight(0) <= 32_000_000) {
                            return when (reader.formatName.lowercase()) {
                                "png" -> "image/png"
                                "jpeg" -> "image/jpeg"
                                else -> fail(Fault.INVALID_INPUT, "Expected a JPEG or PNG image")
                            }
                        }
                    } finally {
                        reader.dispose()
                    }
                }
            }
        } catch (_: IOException) {
            fail(Fault.INVALID_INPUT, "Expected a JPEG or PNG image")
        }
        fail(Fault.INVALID_INPUT, "Expected a JPEG or PNG image")
    }
}

data class HistoryEntry(val version: Long, val actorId: UUID, val action: String, val status: Status, val occurredAt: java.time.Instant)
