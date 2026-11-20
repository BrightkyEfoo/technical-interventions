package cm.brightky.serviceopsjava.requests.infrastructure.persistence;

import cm.brightky.serviceopsjava.requests.application.port.out.RequestRepository;
import cm.brightky.serviceopsjava.requests.domain.Request;
import cm.brightky.serviceopsjava.requests.domain.RequestId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRequestRepositoryTest {
    @Test
    void savesAndFindsRequestByIdValue() {
        RequestRepository repository = new InMemoryRequestRepository();
        String uuidText = "550e8400-e29b-41d4-a716-446655440000";
        RequestId id = new RequestId(UUID.fromString(uuidText));
        Request request = new Request(id, "Porte bloquée");

        Request saved = repository.save(request);
        RequestId lookupId = new RequestId(UUID.fromString(uuidText));
        Request found = repository.findById(lookupId).orElseThrow();

        assertNotNull(saved);
        assertEquals(id, saved.id());
        assertEquals(request.title(), saved.title());
        assertEquals(id, found.id());
        assertEquals(request.title(), found.title());
    }

    @Test
    void returnsEmptyForUnknownIdEvenWhenAnotherRequestExists() {
        RequestRepository repository = new InMemoryRequestRepository();
        RequestId savedId = new RequestId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        RequestId unknownId = new RequestId(UUID.fromString("550e8400-e29b-41d4-a716-446655440001"));
        repository.save(new Request(savedId, "Porte bloquée"));

        assertTrue(repository.findById(unknownId).isEmpty());
    }
}
