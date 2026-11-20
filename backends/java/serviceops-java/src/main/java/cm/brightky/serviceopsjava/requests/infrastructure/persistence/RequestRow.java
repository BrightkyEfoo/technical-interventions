package cm.brightky.serviceopsjava.requests.infrastructure.persistence;

import cm.brightky.serviceopsjava.requests.domain.*;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "requests")
class RequestRow {
    @Id UUID id;
    String title;
    @Enumerated(EnumType.STRING) RequestStatus status;
    @Column(name = "assignee_id") UUID assigneeId;
    @Column(name = "owner_id") UUID ownerId;
    @Enumerated(EnumType.STRING) Priority priority;
    @Column(name = "created_at") Instant createdAt;
    @Column(name = "updated_at") Instant updatedAt;
    @Version Long version;
    String report;

    protected RequestRow() {}

    RequestRow(Request request) {
        id = request.id().value();
        title = request.title();
        ownerId = request.ownerId();
        priority = request.priority();
        createdAt = request.createdAt();
        update(request);
    }

    void update(Request request) {
        status = request.status();
        assigneeId = request.assigneeId();
        report = request.report();
        updatedAt = Instant.now();
    }

    Request toDomain() {
        return Request.restore(new RequestId(id), title, ownerId, priority, status, assigneeId, createdAt, version, report);
    }
}
