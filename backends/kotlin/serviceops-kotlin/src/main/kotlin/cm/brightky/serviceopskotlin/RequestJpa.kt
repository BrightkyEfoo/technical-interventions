package cm.brightky.serviceopskotlin

import jakarta.persistence.*
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Locale
import java.util.UUID

@Entity
@Table(name = "requests")
class RequestRow(
    @Id var id: UUID,
    var title: String,
    @Enumerated(EnumType.STRING) var status: Status,
    @Column(name = "assignee_id") var assigneeId: UUID?,
    @Column(name = "owner_id") var ownerId: UUID?,
    @Enumerated(EnumType.STRING) var priority: Priority,
    @Version var version: Long?,
    @Column(name = "created_at") var createdAt: Instant,
    @Column(name = "updated_at") var updatedAt: Instant,
    var report: String?,
) {
    fun snapshot() = Intervention(id, title, status, assigneeId, ownerId, priority, version ?: 0, createdAt, report)
}

data class RequestPage(val items: List<Intervention>, val total: Long, val page: Int, val size: Int)

@Repository
class Requests(private val em: EntityManager) {
    fun find(id: UUID): Intervention? = em.find(RequestRow::class.java, id)?.snapshot()

    fun save(request: Intervention): Intervention {
        val row = em.find(RequestRow::class.java, request.id)?.also {
            request.withVersion(it.version ?: 0)
            it.status = request.status
            it.assigneeId = request.assigneeId
            it.report = request.report
            it.updatedAt = Instant.now()
        } ?: RequestRow(
            request.id, request.title, request.status, request.assigneeId, request.ownerId, request.priority,
            null, request.createdAt, Instant.now(), request.report
        ).also(em::persist)
        em.flush()
        return row.snapshot()
    }

    fun search(actor: Account, filter: String, status: Status?, page: Int, size: Int): RequestPage {
        val filterByAccount = when (actor.role) {
            Role.SUPER_ADMIN, Role.MANAGER -> ""
            Role.REQUESTER -> " and r.ownerId = :actor"
            Role.TECHNICIAN -> " and r.assigneeId = :actor"
        }
        val filterByStatus = if (status != null) " and r.status = :status" else ""
        val where = " from RequestRow r where locate(:needle, lower(r.title)) > 0" + filterByAccount + filterByStatus
        val list = em.createQuery("select r" + where + " order by r.createdAt desc, r.id", RequestRow::class.java)
        val count = em.createQuery("select count(r)" + where, Long::class.javaObjectType)
        listOf(list, count).forEach { query ->
            query.setParameter("needle", filter.lowercase(Locale.ROOT))
            if (filterByAccount.isNotEmpty()) query.setParameter("actor", actor.id)
            if (status != null) query.setParameter("status", status)
        }
        val items = list.setFirstResult(page * size).setMaxResults(size).resultList.map(RequestRow::snapshot)
        return RequestPage(items, count.singleResult, page, size)
    }
}
