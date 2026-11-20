package cm.brightky.serviceopsjava.requests.infrastructure.web;

import cm.brightky.serviceopsjava.shared.error.ErrorCode;
import cm.brightky.serviceopsjava.shared.error.ServiceOpsException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestExceptionHandlerTest {
    @Test
    void mapsRequestNotFoundTo404() {
        var exception = new ServiceOpsException(ErrorCode.REQUEST_NOT_FOUND, "Request not found: sample-id");
        var handler = new RequestExceptionHandler();

        ProblemDetail problem = handler.handleServiceOpsException(exception);

        assertEquals(HttpStatus.NOT_FOUND.value(), problem.getStatus());
        assertEquals("Request not found", problem.getTitle());
        assertEquals(exception.getMessage(), problem.getDetail());
        assertEquals("REQUEST_NOT_FOUND", problem.getProperties().get("code"));
    }
}
