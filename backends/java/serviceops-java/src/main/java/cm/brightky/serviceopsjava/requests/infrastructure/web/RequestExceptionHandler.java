package cm.brightky.serviceopsjava.requests.infrastructure.web;

import cm.brightky.accounts.KeycloakAccounts.DirectoryFailure;
import org.springframework.http.HttpStatusCode;

import cm.brightky.serviceopsjava.shared.error.ServiceOpsException;
import cm.brightky.serviceopsjava.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class RequestExceptionHandler {
    @ExceptionHandler(DirectoryFailure.class)
    public ProblemDetail accountDirectoryFailure(DirectoryFailure failure) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(failure.status()), failure.getMessage());
        problem.setProperty("code", failure.code());
        return problem;
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class})
    public ProblemDetail handleInvalidInput() {
        return handleServiceOpsException(new ServiceOpsException(ErrorCode.INVALID_INPUT, "Check request fields, parameters and headers"));
    }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock() {
        return handleServiceOpsException(new ServiceOpsException(ErrorCode.VERSION_CONFLICT, "The request changed since it was last read"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleLargeAttachment() {
        return handleServiceOpsException(new ServiceOpsException(ErrorCode.ATTACHMENT_TOO_LARGE, "Image exceeds 5 MiB"));
    }
    @ExceptionHandler(ServiceOpsException.class)
    public ProblemDetail handleServiceOpsException(ServiceOpsException exception) {
        HttpError error = switch (exception.code()) {
            case INVALID_TITLE -> new HttpError(HttpStatus.BAD_REQUEST, "Invalid request title");
            case REQUEST_NOT_FOUND -> new HttpError(HttpStatus.NOT_FOUND, "Request not found");
            case USER_NOT_FOUND -> new HttpError(HttpStatus.NOT_FOUND, "User not found");
            case ASSIGNMENT_FORBIDDEN -> new HttpError(HttpStatus.FORBIDDEN, "Assignment forbidden");
            case INVALID_ASSIGNEE -> new HttpError(HttpStatus.BAD_REQUEST, "Invalid assignee");
            case REQUEST_ASSIGNMENT_CONFLICT -> new HttpError(HttpStatus.CONFLICT, "Assignment conflict");
            case REQUEST_FORBIDDEN -> new HttpError(HttpStatus.FORBIDDEN, "Request forbidden");
            case REQUEST_STATE_CONFLICT, VERSION_CONFLICT, IDEMPOTENCY_CONFLICT ->
                    new HttpError(HttpStatus.CONFLICT, "Request conflict");
            case INVALID_REPORT, INVALID_INPUT -> new HttpError(HttpStatus.BAD_REQUEST, "Invalid input");
            case UNAUTHENTICATED -> new HttpError(HttpStatus.UNAUTHORIZED, "Authentication required");
            case ATTACHMENT_TOO_LARGE -> new HttpError(HttpStatus.PAYLOAD_TOO_LARGE, "Attachment too large");
        };

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(error.status(), exception.getMessage());
        problem.setTitle(error.title());
        problem.setProperty("code", exception.code().name());
        return problem;
    }

    private record HttpError(HttpStatus status, String title) {
    }
}
