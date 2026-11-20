package cm.brightky.serviceopsjava.requests.application;

import cm.brightky.serviceopsjava.requests.application.port.out.RequestRepository;
import cm.brightky.serviceopsjava.requests.domain.Request;
import cm.brightky.serviceopsjava.requests.domain.RequestStatus;
import cm.brightky.serviceopsjava.requests.infrastructure.persistence.InMemoryRequestRepository;
import cm.brightky.serviceopsjava.shared.error.ErrorCode;
import cm.brightky.serviceopsjava.shared.error.ServiceOpsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CreateRequestTest {
    @Test
    void savesOpenRequestWithNormalizedTitle() {
        RequestRepository repository = new InMemoryRequestRepository();
        CreateRequest createRequest = new CreateRequest(repository);

        Request created = createRequest.execute("  Porte bloquée  ");

        Request stored = repository.findById(created.id()).orElseThrow();
        assertEquals(created.id(), stored.id());
        assertEquals("Porte bloquée", stored.title());
        assertEquals(RequestStatus.OPEN, stored.status());
    }

    @Test
    void rejectsInvalidTitleBeforeSaving() {
        RequestRepository repository = mock(RequestRepository.class);
        CreateRequest createRequest = new CreateRequest(repository);

        ServiceOpsException exception = assertThrows(ServiceOpsException.class, () -> createRequest.execute("  LED  "));

        assertEquals(ErrorCode.INVALID_TITLE, exception.code());
        verifyNoInteractions(repository);
    }
}
