package cm.brightky.serviceopskotlin

import java.time.Instant
import java.util.UUID

enum class Role { SUPER_ADMIN, MANAGER, TECHNICIAN, REQUESTER }
enum class Status { OPEN, ASSIGNED, IN_PROGRESS, RESOLVED, CLOSED }
enum class Priority { LOW, NORMAL, HIGH }

data class Account(val id: UUID, val role: Role, val username: String = "")

enum class Fault(val http: Int, val title: String) {
    INVALID_TITLE(400, "Invalid request title"),
    INVALID_INPUT(400, "Invalid input"),
    INVALID_REPORT(400, "Invalid input"),
    INVALID_ASSIGNEE(400, "Invalid assignee"),
    UNAUTHENTICATED(401, "Authentication required"),
    REQUEST_FORBIDDEN(403, "Request forbidden"),
    ASSIGNMENT_FORBIDDEN(403, "Assignment forbidden"),
    REQUEST_NOT_FOUND(404, "Request not found"),
    USER_NOT_FOUND(404, "User not found"),
    VERSION_CONFLICT(409, "Request conflict"),
    IDEMPOTENCY_CONFLICT(409, "Request conflict"),
    REQUEST_STATE_CONFLICT(409, "Request conflict"),
    REQUEST_ASSIGNMENT_CONFLICT(409, "Assignment conflict"),
    ATTACHMENT_TOO_LARGE(413, "Attachment too large")
}

class DomainFailure(val fault: Fault, detail: String) : RuntimeException(detail)

fun fail(fault: Fault, detail: String): Nothing = throw DomainFailure(fault, detail)

data class Intervention(
    val id: UUID,
    val title: String,
    val status: Status,
    val assigneeId: UUID?,
    val ownerId: UUID?,
    val priority: Priority,
    val version: Long,
    val createdAt: Instant,
    val report: String?,
) {
    fun visibleTo(actor: Account) = when (actor.role) {
        Role.SUPER_ADMIN, Role.MANAGER -> true
        Role.REQUESTER -> actor.id == ownerId
        Role.TECHNICIAN -> actor.id == assigneeId
    }

    fun withVersion(expected: Long): Intervention {
        if (version != expected) fail(Fault.VERSION_CONFLICT, "The request changed since it was last read")
        return this
    }

    fun assign(actor: Account, technician: Account): Intervention {
        if (actor.role != Role.MANAGER) fail(Fault.ASSIGNMENT_FORBIDDEN, "Only a manager can assign a request")
        if (technician.role != Role.TECHNICIAN) fail(Fault.INVALID_ASSIGNEE, "A request must be assigned to a technician")
        if (status !in listOf(Status.OPEN, Status.ASSIGNED)) {
            fail(Fault.REQUEST_ASSIGNMENT_CONFLICT, "A request can only be assigned before work starts")
        }
        return copy(status = Status.ASSIGNED, assigneeId = technician.id)
    }

    fun start(actor: Account): Intervention {
        requireTechnician(actor)
        requireStatus(Status.ASSIGNED)
        return copy(status = Status.IN_PROGRESS)
    }

    fun resolve(actor: Account, report: String): Intervention {
        requireTechnician(actor)
        requireStatus(Status.IN_PROGRESS)
        val cleaned = report.trim { Character.isWhitespace(it) }
        if (cleaned.length !in 10..2000) fail(Fault.INVALID_REPORT, "The report must have 10 to 2000 characters")
        return copy(status = Status.RESOLVED, report = cleaned)
    }

    fun close(actor: Account): Intervention {
        if (actor.role != Role.MANAGER && (actor.role != Role.REQUESTER || actor.id != ownerId)) {
            fail(Fault.REQUEST_FORBIDDEN, "Only the owner or a manager can close this request")
        }
        requireStatus(Status.RESOLVED)
        return copy(status = Status.CLOSED)
    }

    private fun requireTechnician(actor: Account) {
        if (actor.role != Role.TECHNICIAN || actor.id != assigneeId) {
            fail(Fault.REQUEST_FORBIDDEN, "Only the assigned technician can perform this action")
        }
    }

    private fun requireStatus(required: Status) {
        if (status != required) fail(Fault.REQUEST_STATE_CONFLICT, "Expected status $required")
    }

    companion object {
        fun open(title: String, ownerId: UUID, priority: Priority): Intervention {
            val cleaned = title.trim { Character.isWhitespace(it) }
            if (cleaned.length < 5) fail(Fault.INVALID_TITLE, "The title must have at least 5 characters")
            if (cleaned.length > 120) fail(Fault.INVALID_TITLE, "The title must have at most 120 characters")
            return Intervention(UUID.randomUUID(), cleaned, Status.OPEN, null, ownerId, priority, 0, Instant.now(), null)
        }
    }
}

val demoAccounts = listOf("manager" to Role.MANAGER, "technician" to Role.TECHNICIAN,
    "requester" to Role.REQUESTER, "technician2" to Role.TECHNICIAN)
    .mapIndexed { index, (username, role) ->
        val suffix = (index + 1).toString().padStart(12, '0')
        Account(UUID.fromString("00000000-0000-4000-8000-$suffix"), role, username)
    }.associateBy(Account::id)
