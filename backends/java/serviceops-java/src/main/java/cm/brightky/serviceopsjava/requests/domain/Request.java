package cm.brightky.serviceopsjava.requests.domain;

import cm.brightky.serviceopsjava.shared.error.ErrorCode;
import cm.brightky.serviceopsjava.shared.error.ServiceOpsException;
import cm.brightky.serviceopsjava.users.domain.Role;
import cm.brightky.serviceopsjava.users.domain.User;

import java.time.Instant;
import java.util.UUID;

public class Request {
    private final RequestId id;
    private RequestStatus status;
    private String title;
    private UUID assigneeId;
    private UUID ownerId;
    private Priority priority = Priority.NORMAL;
    private Instant createdAt = Instant.now();
    private long version;
    private String report;


    public Request(RequestId id, String title) {
        var cleanedTitle = title.strip();
        var titleLength = cleanedTitle.length();

        if(id == null) throw new IllegalArgumentException("The id must not be null");
        if (titleLength < 5) {
            throw new ServiceOpsException(ErrorCode.INVALID_TITLE, "The title must have at least 5 characters");
        }
        if (titleLength > 120) {
            throw new ServiceOpsException(ErrorCode.INVALID_TITLE, "The title must have at most 120 characters");
        }

        this.id = id;
        this.title = cleanedTitle;
        this.status = RequestStatus.OPEN;
    }

    public RequestStatus status() {
        return this.status;
    }

    public String title() {
        return this.title;
    }

    public UUID assigneeId() {
        return assigneeId;
    }

    public RequestId id() {
        return id;
    }

    public Request(RequestId id, String title, UUID ownerId, Priority priority) {
        this(id, title);
        this.ownerId = ownerId;
        this.priority = priority;
    }

    public static Request restore(RequestId id, String title, UUID ownerId, Priority priority,
                                  RequestStatus status, UUID assigneeId, Instant createdAt, long version, String report) {
        var request = new Request(id, title, ownerId, priority);
        request.status = status;
        request.assigneeId = assigneeId;
        request.createdAt = createdAt;
        request.version = version;
        request.report = report;
        return request;
    }

    public UUID ownerId() { return ownerId; }
    public Priority priority() { return priority; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }
    public String report() { return report; }

    public boolean visibleTo(User user) {
        return switch (user.role()) {
            case SUPER_ADMIN, MANAGER -> true;
            case REQUESTER -> user.id().equals(ownerId);
            case TECHNICIAN -> user.id().equals(assigneeId);
        };
    }

    public void checkVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new ServiceOpsException(ErrorCode.VERSION_CONFLICT, "The request changed since it was last read");
        }
    }

    public void resolve(User actor, String report) {
        if (actor.role() != Role.TECHNICIAN || !actor.id().equals(assigneeId)) {
            throw new ServiceOpsException(ErrorCode.REQUEST_FORBIDDEN, "Only the assigned technician can resolve this request");
        }
        requireStatus(RequestStatus.IN_PROGRESS);
        var cleanedReport = report.strip();
        if (cleanedReport.length() < 10 || cleanedReport.length() > 2000) {
            throw new ServiceOpsException(ErrorCode.INVALID_REPORT, "The report must have 10 to 2000 characters");
        }
        this.report = cleanedReport;
        status = RequestStatus.RESOLVED;
    }

    public void close(User actor) {
        if (actor.role() != Role.MANAGER && (actor.role() != Role.REQUESTER || !actor.id().equals(ownerId))) {
            throw new ServiceOpsException(ErrorCode.REQUEST_FORBIDDEN, "Only the owner or a manager can close this request");
        }
        requireStatus(RequestStatus.RESOLVED);
        status = RequestStatus.CLOSED;
    }

    private void requireStatus(RequestStatus required) {
        if (status != required) {
            throw new ServiceOpsException(ErrorCode.REQUEST_STATE_CONFLICT, "Expected status " + required);
        }
    }

    public void assignTo(User actor, User technician) {
        if (actor == null || actor.role() != Role.MANAGER) {
            throw new ServiceOpsException(ErrorCode.ASSIGNMENT_FORBIDDEN, "Only a manager can assign a request");
        }
        if (technician == null || technician.role() != Role.TECHNICIAN) {
            throw new ServiceOpsException(ErrorCode.INVALID_ASSIGNEE, "A request must be assigned to a technician");
        }
        if (status != RequestStatus.OPEN && status != RequestStatus.ASSIGNED) {
            throw new ServiceOpsException(
                    ErrorCode.REQUEST_ASSIGNMENT_CONFLICT, "A request can only be assigned before work starts");
        }

        this.assigneeId = technician.id();
        this.status = RequestStatus.ASSIGNED;
    }

    public void start(User technician) {
        if (technician == null || technician.role() != Role.TECHNICIAN || !technician.id().equals(this.assigneeId)) {
            throw new IllegalArgumentException("A request must be started by the assigned technician");
        }

        if (status != RequestStatus.ASSIGNED) {
            throw new IllegalStateException("A request can only be started when it was assigned");
        }

        this.status = RequestStatus.IN_PROGRESS;
    }
}
