package cm.brightky.serviceopsjava.requests.infrastructure.persistence;

import cm.brightky.serviceopsjava.requests.application.port.out.RequestRepository;
import cm.brightky.serviceopsjava.requests.domain.*;
import cm.brightky.serviceopsjava.users.domain.User;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;

@Repository
@Profile("postgres")
@Transactional
class PostgresRequestRepository implements RequestRepository {
    private final EntityManager entityManager;

    PostgresRequestRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Request save(Request request) {
        var row = entityManager.find(RequestRow.class, request.id().value());
        if (row == null) {
            row = new RequestRow(request);
            entityManager.persist(row);
        } else {
            request.checkVersion(row.version);
            row.update(request);
        }
        entityManager.flush();
        return row.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Request> findById(RequestId id) {
        return Optional.ofNullable(entityManager.find(RequestRow.class, id.value())).map(RequestRow::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Page search(User actor, String query, RequestStatus status, int page, int size) {
        var where = new StringBuilder(" from RequestRow r where locate(:query, lower(r.title)) > 0");
        var parameters = new HashMap<String, Object>();
        parameters.put("query", query.toLowerCase(Locale.ROOT));
        switch (actor.role()) {
            case REQUESTER -> {
                where.append(" and r.ownerId = :actorId");
                parameters.put("actorId", actor.id());
            }
            case TECHNICIAN -> {
                where.append(" and r.assigneeId = :actorId");
                parameters.put("actorId", actor.id());
            }
            case SUPER_ADMIN, MANAGER -> {}
        }
        if (status != null) {
            where.append(" and r.status = :status");
            parameters.put("status", status);
        }
        var rows = entityManager.createQuery("select r" + where + " order by r.createdAt desc, r.id", RequestRow.class);
        var count = entityManager.createQuery("select count(r)" + where, Long.class);
        parameters.forEach((key, value) -> {
            rows.setParameter(key, value);
            count.setParameter(key, value);
        });
        var items = rows.setFirstResult(Math.multiplyExact(page, size)).setMaxResults(size)
                .getResultList().stream().map(RequestRow::toDomain).toList();
        return new Page(items, count.getSingleResult());
    }
}
