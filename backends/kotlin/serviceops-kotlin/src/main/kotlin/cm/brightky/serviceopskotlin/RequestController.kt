package cm.brightky.serviceopskotlin

import cm.brightky.accounts.KeycloakAccounts.DirectoryFailure
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.http.converter.HttpMessageNotReadableException
import java.util.UUID

data class CreateBody(@field:NotBlank val title: String?, val priority: Priority? = null)
data class AssignBody(
    @field:NotNull val technicianId: UUID?,
    @field:PositiveOrZero val expectedVersion: Long?,
    val actorId: UUID? = null,
)
data class VersionBody(@field:NotNull @field:PositiveOrZero val expectedVersion: Long?)
data class ResolveBody(@field:NotNull @field:PositiveOrZero val expectedVersion: Long?, @field:NotBlank val report: String?)
data class CommentBody(@field:NotNull @field:PositiveOrZero val expectedVersion: Long?, @field:NotBlank val body: String?)

@RestController
@RequestMapping("/api/v1/requests")
class RequestController(private val flow: RequestWorkflow, private val actors: Actors) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(request: HttpServletRequest, @Valid @RequestBody body: CreateBody, @RequestHeader("Idempotency-Key", required = false) key: String?) =
        flow.create(actors.current(request), body.title ?: missing("title"), body.priority ?: Priority.NORMAL, key)

    @GetMapping
    fun list(
        request: HttpServletRequest, @RequestParam(defaultValue = "") query: String,
        @RequestParam(required = false) status: Status?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int,
    ) = flow.list(actors.read(request), query, status, page, size)

    @GetMapping("/{id}")
    fun get(request: HttpServletRequest, @PathVariable id: UUID) = flow.get(id, actors.read(request))

    @PostMapping("/{id}/assign")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun assign(request: HttpServletRequest, @PathVariable id: UUID, @Valid @RequestBody body: AssignBody) {
        flow.assign(id, actors.current(request, body.actorId), body.technicianId ?: missing("technicianId"),
            body.expectedVersion ?: missing("expectedVersion"))
    }

    @PostMapping("/{id}/start")
    fun start(request: HttpServletRequest, @PathVariable id: UUID, @Valid @RequestBody body: VersionBody) =
        flow.start(id, actors.current(request), body.expectedVersion ?: missing("expectedVersion"))

    @PostMapping("/{id}/resolve")
    fun resolve(request: HttpServletRequest, @PathVariable id: UUID, @Valid @RequestBody body: ResolveBody,
                @RequestHeader("Idempotency-Key", required = false) key: String?) =
        flow.resolve(id, actors.current(request), body.expectedVersion ?: missing("expectedVersion"), body.report ?: missing("report"), key)

    @PostMapping("/{id}/close")
    fun close(request: HttpServletRequest, @PathVariable id: UUID, @Valid @RequestBody body: VersionBody) =
        flow.close(id, actors.current(request), body.expectedVersion ?: missing("expectedVersion"))

    @GetMapping("/{id}/comments")
    fun comments(request: HttpServletRequest, @PathVariable id: UUID) = flow.comments(id, actors.read(request))

    @GetMapping("/{id}/history")
    fun history(request: HttpServletRequest, @PathVariable id: UUID) = flow.history(id, actors.read(request))

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun comment(request: HttpServletRequest, @PathVariable id: UUID, @Valid @RequestBody body: CommentBody) =
        flow.comment(id, actors.current(request), body.expectedVersion ?: missing("expectedVersion"), body.body ?: missing("body"))

    @PostMapping("/{id}/attachments", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun attach(request: HttpServletRequest, @PathVariable id: UUID, @RequestParam @PositiveOrZero expectedVersion: Long,
               @RequestPart file: MultipartFile) =
        mapOf("id" to flow.attach(id, actors.current(request), expectedVersion, file.bytes))

    @GetMapping("/{id}/attachments/{fileId}")
    fun download(request: HttpServletRequest, @PathVariable id: UUID, @PathVariable fileId: UUID): ResponseEntity<ByteArray> {
        val (type, bytes) = flow.download(id, fileId, actors.read(request))
        return ResponseEntity.ok().header("X-Content-Type-Options", "nosniff").contentType(MediaType.parseMediaType(type)).body(bytes)
    }

    private fun missing(field: String): Nothing = fail(Fault.INVALID_INPUT, "$field is required")
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(DirectoryFailure::class)
    fun directoryFailure(failure: DirectoryFailure): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(failure.status()), failure.message.orEmpty()).apply {
            setProperty("code", failure.code())
        }
    @ExceptionHandler(MethodArgumentNotValidException::class, ServletRequestBindingException::class,
        MethodArgumentTypeMismatchException::class, HandlerMethodValidationException::class,
        HttpMessageNotReadableException::class)
    fun invalidInput() = problem(DomainFailure(Fault.INVALID_INPUT, "Check request fields, parameters and headers"))

    @ExceptionHandler(DomainFailure::class)
    fun problem(exception: DomainFailure): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(exception.fault.http), exception.message.orEmpty()).apply {
            title = exception.fault.title
            setProperty("code", exception.fault.name)
        }

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun version() = problem(DomainFailure(Fault.VERSION_CONFLICT, "The request changed since it was last read"))

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun photoSize() = problem(DomainFailure(Fault.ATTACHMENT_TOO_LARGE, "Image exceeds 5 MiB"))
}
