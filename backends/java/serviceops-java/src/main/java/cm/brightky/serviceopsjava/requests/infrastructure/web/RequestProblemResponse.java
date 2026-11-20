package cm.brightky.serviceopsjava.requests.infrastructure.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;

@Schema(
        description = "Réponse produite par le gestionnaire des erreurs métier",
        requiredProperties = {"status", "detail", "code"}
)
public record RequestProblemResponse(
        URI type,
        String title,
        int status,
        String detail,
        @Schema(format = "uri-reference")
        URI instance,
        @Schema(description = "Code métier stable")
        String code
) {
}
