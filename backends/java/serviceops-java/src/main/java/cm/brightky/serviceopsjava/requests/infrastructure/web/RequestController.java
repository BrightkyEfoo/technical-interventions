package cm.brightky.serviceopsjava.requests.infrastructure.web;

import cm.brightky.serviceopsjava.requests.application.AssignRequest;
import cm.brightky.serviceopsjava.requests.application.AssignRequestCommand;
import cm.brightky.serviceopsjava.requests.application.CreateRequest;
import cm.brightky.serviceopsjava.requests.domain.RequestId;
import cm.brightky.serviceopsjava.requests.domain.RequestStatus;
import cm.brightky.serviceopsjava.shared.error.ErrorCode;
import cm.brightky.serviceopsjava.shared.error.ServiceOpsException;
import cm.brightky.serviceopsjava.shared.security.ActorResolver;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.Map;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/requests")
public class RequestController {
    private final CreateRequest createRequestUseCase;
    private final AssignRequest assignRequestUseCase;
    private final RequestWorkflow workflow;
    private final ActorResolver actors;

    public RequestController(CreateRequest createRequestUseCase, AssignRequest assignRequestUseCase,
                             RequestWorkflow workflow, ActorResolver actors) {
        this.createRequestUseCase = createRequestUseCase;
        this.assignRequestUseCase = assignRequestUseCase;
        this.workflow = workflow;
        this.actors = actors;
    }

    @ApiResponse(
            responseCode = "201",
            description = "Demande créée",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = RequestResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "JSON mal formé ou titre invalide. Le format ci-dessous décrit le refus lié à la longueur du titre.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = RequestProblemResponse.class),
                    examples = @ExampleObject(name = "invalidTitle", value = """
                            {
                              "type": "about:blank",
                              "title": "Invalid request title",
                              "status": 400,
                              "detail": "The title must have at least 5 characters",
                              "instance": "/api/v1/requests",
                              "code": "INVALID_TITLE"
                            }
                            """)
            )
    )
    @PostMapping("")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestResponse createRequest(@Valid @RequestBody CreateRequestBody body,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @RequestHeader(value = "X-Demo-Actor", required = false) UUID demoId,
                                         @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        if (actors.anonymousDemo(jwt, demoId)) {
            return RequestResponse.from(createRequestUseCase.execute(body.title()));
        }
        return workflow.create(actors.require(jwt, demoId), body, key);
    }

    @ApiResponse(responseCode = "204", description = "Demande affectée", content = @Content)
    @ApiResponse(
            responseCode = "400",
            description = "Identifiant ou corps invalide. Le format ci-dessous correspond au code INVALID_ASSIGNEE.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = RequestProblemResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "403",
            description = "Seul un responsable peut affecter la demande (ASSIGNMENT_FORBIDDEN)",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = RequestProblemResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Demande ou utilisateur introuvable (REQUEST_NOT_FOUND ou USER_NOT_FOUND)",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = RequestProblemResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "409",
            description = "Le statut de la demande ne permet plus l’affectation (REQUEST_ASSIGNMENT_CONFLICT)",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = RequestProblemResponse.class)
            )
    )
    @PostMapping("/{id}/assign")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignRequest(@PathVariable UUID id, @Valid @RequestBody AssignRequestBody body,
                              @RequestHeader(value = "X-Demo-Actor", required = false) UUID demoId,
                              @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        var actor = actors.require(jwt, demoId == null ? body.actorId() : demoId);
        if (jwt == null && body.expectedVersion() == null) {
            assignRequestUseCase.execute(new AssignRequestCommand(new RequestId(id), actor.id(), body.technicianId()));
            return;
        }
        if (body.expectedVersion() == null) {
            throw new ServiceOpsException(ErrorCode.INVALID_INPUT, "expectedVersion is required");
        }
        workflow.assign(id, actor, body.technicianId(), body.expectedVersion());
    }

    @GetMapping
    public RequestWorkflow.RequestPage list(@RequestParam(defaultValue = "") String query,
                                            @RequestParam(required = false) RequestStatus status,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestHeader(value = "X-Demo-Actor", required = false) UUID demoId,
                                            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return workflow.list(actors.requireRead(jwt, demoId), query, status, page, size);
    }

    @GetMapping("/{id}")
    public RequestResponse get(@PathVariable UUID id,
                               @RequestHeader(value = "X-Demo-Actor", required = false) UUID demoId,
                               @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return workflow.get(id, actors.requireRead(jwt, demoId));
    }

    @PostMapping("/{id}/start")
    public RequestResponse start(@PathVariable UUID id, @Valid @RequestBody VersionBody body,
                                 @RequestHeader(value = "X-Demo-Actor", required = false) UUID demoId,
                                 @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return workflow.start(id, actors.require(jwt, demoId), body.expectedVersion());
    }

    @PostMapping("/{id}/resolve")
    public RequestResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveBody body,
                                   @RequestHeader("Idempotency-Key") String key,
                                   @RequestHeader(value = "X-Demo-Actor", required = false) UUID demoId,
                                   @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return workflow.resolve(id, actors.require(jwt, demoId), body.expectedVersion(), body.report(), key);
    }

    @PostMapping("/{id}/close")
    public RequestResponse close(@PathVariable UUID id, @Valid @RequestBody VersionBody body,
                                 @RequestHeader(value = "X-Demo-Actor", required = false) UUID demoId,
                                 @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return workflow.close(id, actors.require(jwt, demoId), body.expectedVersion());
    }

    @GetMapping("/{id}/comments")
    public java.util.List<Map<String, Object>> comments(@PathVariable UUID id,
                                                       @RequestHeader(value = "X-Demo-Actor", required = false) UUID demoId,
                                                       @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return workflow.comments(id, actors.requireRead(jwt, demoId));
    }

    @GetMapping("/{id}/history")
    public java.util.List<RequestWorkflow.HistoryEntry> history(@PathVariable UUID id,
                    @RequestHeader(value = "X-Demo-Actor", required = false) UUID demoId,
                    @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return workflow.history(id, actors.requireRead(jwt, demoId));
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> comment(@PathVariable UUID id, @Valid @RequestBody CommentBody body,
                                       @RequestHeader(value = "X-Demo-Actor", required = false) UUID demoId,
                                       @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return workflow.comment(id, actors.require(jwt, demoId), body.expectedVersion(), body.body());
    }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> attach(@PathVariable UUID id, @RequestParam @PositiveOrZero long expectedVersion,
                                    @RequestPart MultipartFile file,
                                    @RequestHeader(value = "X-Demo-Actor", required = false) UUID demoId,
                                    @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) throws IOException {
        return Map.of("id", workflow.attach(id, actors.require(jwt, demoId), expectedVersion, file.getBytes()));
    }

    @GetMapping("/{id}/attachments/{fileId}")
    public ResponseEntity<byte[]> attachment(@PathVariable UUID id, @PathVariable UUID fileId,
                                             @RequestHeader(value = "X-Demo-Actor", required = false) UUID demoId,
                                             @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        var content = workflow.attachment(id, fileId, actors.requireRead(jwt, demoId));
        return ResponseEntity.ok().header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType((String) content.get("media_type")))
                .body((byte[]) content.get("content"));
    }

    record VersionBody(@NotNull @PositiveOrZero Long expectedVersion) {}
    record ResolveBody(@NotNull @PositiveOrZero Long expectedVersion, @NotBlank String report) {}
    record CommentBody(@NotNull @PositiveOrZero Long expectedVersion, @NotBlank String body) {}
}
