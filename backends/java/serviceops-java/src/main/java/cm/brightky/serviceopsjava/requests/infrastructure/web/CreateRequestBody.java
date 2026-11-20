package cm.brightky.serviceopsjava.requests.infrastructure.web;

import jakarta.validation.constraints.NotBlank;
import cm.brightky.serviceopsjava.requests.domain.Priority;

public record CreateRequestBody (
        @NotBlank(message = "The title can't be blank")
        String title,
        Priority priority
) { }
