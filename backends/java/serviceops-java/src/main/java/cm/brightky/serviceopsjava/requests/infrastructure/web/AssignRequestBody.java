package cm.brightky.serviceopsjava.requests.infrastructure.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignRequestBody(
        @Schema(description = "Identifiant du responsable utilisé pour la démonstration, en attendant OIDC")
        UUID actorId,
        @NotNull
        UUID technicianId,
        @jakarta.validation.constraints.PositiveOrZero Long expectedVersion
) {
}
