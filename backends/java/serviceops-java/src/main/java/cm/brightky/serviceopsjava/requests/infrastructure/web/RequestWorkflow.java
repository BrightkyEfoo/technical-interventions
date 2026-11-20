package cm.brightky.serviceopsjava.requests.infrastructure.web;

import cm.brightky.accounts.KeycloakAccounts;
import cm.brightky.serviceopsjava.requests.application.port.out.RequestRepository;
import cm.brightky.serviceopsjava.requests.domain.*;
import cm.brightky.serviceopsjava.shared.error.ErrorCode;
import cm.brightky.serviceopsjava.shared.error.ServiceOpsException;
import cm.brightky.serviceopsjava.users.application.port.out.UserRepository;
import cm.brightky.serviceopsjava.users.domain.Role;
import cm.brightky.serviceopsjava.users.domain.User;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@Transactional
class RequestWorkflow {
    private final RequestRepository requests;
    private final UserRepository users;
    private final KeycloakAccounts accounts;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectMapper json;

    RequestWorkflow(RequestRepository requests, UserRepository users, ObjectProvider<JdbcTemplate> jdbc,
                    ObjectMapper json, KeycloakAccounts accounts) {
        this.requests = requests;
        this.users = users;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.json = json;
    }

    RequestResponse create(User actor, CreateRequestBody body, String key) {
        if (actor.role() != Role.REQUESTER && actor.role() != Role.MANAGER) {
            throw fail(ErrorCode.REQUEST_FORBIDDEN, "This role cannot create requests");
        }
        var priority = body.priority() == null ? Priority.NORMAL : body.priority();
        return idempotent(actor.id() + "/create", key, json.writeValueAsString(List.of(body.title().strip(), priority)), () ->
                persist(new Request(new RequestId(UUID.randomUUID()), body.title(), actor.id(), priority), actor, "CREATE"));
    }

    @Transactional(readOnly = true)
    RequestResponse get(UUID id, User actor) {
        return RequestResponse.from(visibleRequest(id, actor));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    RequestPage list(User actor, String query, RequestStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || page > 100_000 || query.length() > 120) {
            throw fail(ErrorCode.INVALID_INPUT, "Invalid pagination or search filter");
        }
        var result = requests.search(actor, query.strip(), status, page, size);
        return new RequestPage(result.items().stream().map(RequestResponse::from).toList(), result.total(), page, size);
    }

    void assign(UUID id, User actor, UUID technicianId, Long expectedVersion) {
        var request = visibleRequest(id, actor);
        if (actor.role() != Role.MANAGER) {
            throw fail(ErrorCode.ASSIGNMENT_FORBIDDEN, "Only a manager can assign a request");
        }
        checkVersion(request, expectedVersion);
        var candidate = accounts.isConfigured()
                ? accounts.technician(technicianId).map(tech -> new User(tech.id(), tech.name(), Role.TECHNICIAN))
                : users.findById(technicianId);
        var technician = candidate
                .orElseThrow(() -> fail(ErrorCode.USER_NOT_FOUND, "Technician not found: " + technicianId));
        request.assignTo(actor, technician);
        var saved = persist(request, actor, "ASSIGN");
        var database = jdbc.getIfAvailable();
        if (database != null) {
            database.update("INSERT INTO request_outbox(id, request_id, request_version, recipient_id) VALUES(?, ?, ?, ?)",
                    UUID.randomUUID(), saved.id(), saved.version(), technicianId);
        }
    }

    RequestResponse start(UUID id, User actor, long expectedVersion) {
        var request = visibleRequest(id, actor);
        request.checkVersion(expectedVersion);
        try {
            request.start(actor);
        } catch (IllegalArgumentException failure) {
            throw fail(ErrorCode.REQUEST_FORBIDDEN, failure.getMessage());
        } catch (IllegalStateException failure) {
            throw fail(ErrorCode.REQUEST_STATE_CONFLICT, failure.getMessage());
        }
        return persist(request, actor, "START");
    }

    RequestResponse resolve(UUID id, User actor, long expectedVersion, String report, String key) {
        visibleRequest(id, actor);
        var fingerprint = json.writeValueAsString(List.of(expectedVersion, report.strip()));
        return idempotent(actor.id() + "/resolve/" + id, key, fingerprint, () -> {
            var request = visibleRequest(id, actor);
            request.checkVersion(expectedVersion);
            request.resolve(actor, report);
            return persist(request, actor, "RESOLVE");
        });
    }

    RequestResponse close(UUID id, User actor, long expectedVersion) {
        var request = visibleRequest(id, actor);
        request.checkVersion(expectedVersion);
        request.close(actor);
        return persist(request, actor, "CLOSE");
    }

    List<Map<String, Object>> comments(UUID id, User actor) {
        visibleRequest(id, actor);
        return database().queryForList("""
                SELECT id, author_id AS "authorId", body, created_at AS "createdAt"
                FROM request_comments WHERE request_id = ? ORDER BY created_at, id
                """, id);
    }

    Map<String, Object> comment(UUID id, User actor, long version, String body) {
        var request = editableRequest(id, actor, version);
        var cleaned = body.strip();
        if (cleaned.isEmpty() || cleaned.length() > 2000) {
            throw fail(ErrorCode.INVALID_INPUT, "Comment must have 1 to 2000 characters");
        }
        persist(request, actor, "COMMENT");
        return database().queryForMap("""
                INSERT INTO request_comments (id, request_id, author_id, body) VALUES (?, ?, ?, ?)
                RETURNING id, author_id AS "authorId", body, created_at AS "createdAt"
                """, UUID.randomUUID(), id, actor.id(), cleaned);
    }

    UUID attach(UUID id, User actor, long version, byte[] content) {
        var request = editableRequest(id, actor, version);
        var mediaType = ImageType.detect(content);
        var fileId = UUID.randomUUID();
        persist(request, actor, "ATTACH");
        database().update("INSERT INTO request_attachments(id, request_id, media_type, content) VALUES (?, ?, ?, ?)",
                fileId, id, mediaType, content);
        return fileId;
    }

    Map<String, Object> attachment(UUID id, UUID attachmentId, User actor) {
        visibleRequest(id, actor);
        var rows = database().queryForList("SELECT media_type, content FROM request_attachments WHERE request_id = ? AND id = ?",
                id, attachmentId);
        if (rows.isEmpty()) {
            throw fail(ErrorCode.REQUEST_NOT_FOUND, "Attachment not found");
        }
        return rows.getFirst();
    }

    private Request editableRequest(UUID id, User actor, long version) {
        var request = visibleRequest(id, actor);
        request.checkVersion(version);
        if (request.status() == RequestStatus.CLOSED) {
            throw fail(ErrorCode.REQUEST_STATE_CONFLICT, "This request is closed");
        }
        return request;
    }

    @Transactional(readOnly = true)
    List<HistoryEntry> history(UUID id, User actor) {
        visibleRequest(id, actor);
        return database().query("""
                SELECT version, actor_id, action, status, occurred_at
                FROM request_history WHERE request_id = ? ORDER BY version
                """, (row, index) -> new HistoryEntry(row.getLong("version"), row.getObject("actor_id", UUID.class),
                row.getString("action"), RequestStatus.valueOf(row.getString("status")),
                row.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant()), id);
    }

    private RequestResponse persist(Request request, User actor, String action) {
        var saved = requests.save(request);
        var database = jdbc.getIfAvailable();
        if (database != null) {
            database.update("INSERT INTO request_history(request_id, version, actor_id, action, status) VALUES (?,?,?,?,?)",
                    saved.id().value(), saved.version(), actor.id(), action, saved.status().name());
        }
        return RequestResponse.from(saved);
    }

    private Request visibleRequest(UUID id, User actor) {
        return requests.findById(new RequestId(id)).filter(request -> request.visibleTo(actor))
                .orElseThrow(() -> fail(ErrorCode.REQUEST_NOT_FOUND, "Request not found: " + id));
    }

    private void checkVersion(Request request, Long expectedVersion) {
        if (expectedVersion != null) {
            request.checkVersion(expectedVersion);
        }
    }

    private RequestResponse idempotent(String scope, String key, String fingerprint, Supplier<RequestResponse> execute) {
        var database = jdbc.getIfAvailable();
        if (database == null && key == null) {
            return execute.get();
        }
        if (key == null || key.isBlank() || key.length() > 128) {
            throw fail(ErrorCode.INVALID_INPUT, "Idempotency-Key is required and must be at most 128 characters");
        }
        if (database == null) {
            return execute.get();
        }
        database.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", scope + "/" + key);
        var receipt = database.queryForList("""
                SELECT fingerprint, response::text AS response
                FROM request_receipts WHERE scope = ? AND command_key = ?
                """, scope, key);
        if (!receipt.isEmpty()) {
            var existing = receipt.getFirst();
            if (!fingerprint.equals(existing.get("fingerprint"))) {
                throw fail(ErrorCode.IDEMPOTENCY_CONFLICT, "This key is already used with a different command");
            }
            return json.readValue((String) existing.get("response"), RequestResponse.class);
        }
        var result = execute.get();
        database.update("""
                INSERT INTO request_receipts(scope, command_key, fingerprint, response) VALUES(?, ?, ?, CAST(? AS jsonb))
                """, scope, key, fingerprint, json.writeValueAsString(result));
        return result;
    }

    private JdbcTemplate database() {
        var database = jdbc.getIfAvailable();
        if (database == null) {
            throw fail(ErrorCode.INVALID_INPUT, "This operation requires the PostgreSQL profile");
        }
        return database;
    }

    private ServiceOpsException fail(ErrorCode code, String message) {
        return new ServiceOpsException(code, message);
    }

    record RequestPage(List<RequestResponse> items, long total, int page, int size) {}
    record HistoryEntry(long version, UUID actorId, String action, RequestStatus status, java.time.Instant occurredAt) {}
}
