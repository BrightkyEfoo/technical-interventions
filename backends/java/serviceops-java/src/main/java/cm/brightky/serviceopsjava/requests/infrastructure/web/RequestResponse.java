package cm.brightky.serviceopsjava.requests.infrastructure.web;

import cm.brightky.serviceopsjava.requests.domain.RequestStatus;
import cm.brightky.serviceopsjava.requests.domain.Request;
import cm.brightky.serviceopsjava.requests.domain.Priority;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;
import java.time.Instant;

public record RequestResponse(
        UUID id,
        String title,
        RequestStatus status,
        @Schema(types = {"string", "null"}, format = "uuid")
        UUID assigneeId,
        UUID ownerId,
        Priority priority,
        long version,
        Instant createdAt,
        String report
) {
    static RequestResponse from(Request request) {
        return new RequestResponse(request.id().value(), request.title(), request.status(), request.assigneeId(),
                request.ownerId(), request.priority(), request.version(), request.createdAt(), request.report());
    }
}
