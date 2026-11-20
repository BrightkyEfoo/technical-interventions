package cm.brightky.serviceopsjava.requests.application;

import cm.brightky.serviceopsjava.requests.domain.RequestId;

import java.util.UUID;

public record AssignRequestCommand(
        RequestId requestId,
        UUID actorId,
        UUID technicianId
) {
}
