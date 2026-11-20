package cm.brightky.serviceopsjava.requests.infrastructure.persistence;

import cm.brightky.serviceopsjava.requests.application.port.out.RequestRepository;
import cm.brightky.serviceopsjava.requests.domain.Request;
import cm.brightky.serviceopsjava.requests.domain.RequestId;
import cm.brightky.serviceopsjava.requests.domain.RequestStatus;
import cm.brightky.serviceopsjava.users.domain.User;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRequestRepository implements RequestRepository {

    private final ConcurrentHashMap<RequestId, Request> requests = new ConcurrentHashMap<>();

    @Override
    public Request save(Request r) {
        this.requests.put(r.id(), r);
        return r;
    }

    @Override
    public Optional<Request> findById(RequestId id) {
        return Optional.ofNullable(this.requests.get(id));
    }

    @Override
    public Page search(User actor, String query, RequestStatus status, int page, int size) {
        var matches = requests.values().stream()
                .filter(request -> request.visibleTo(actor))
                .filter(request -> status == null || request.status() == status)
                .filter(request -> request.title().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(Request::createdAt).reversed().thenComparing(request -> request.id().value()))
                .toList();
        return new Page(matches.stream().skip((long) page * size).limit(size).toList(), matches.size());
    }
}
