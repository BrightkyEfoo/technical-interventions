package cm.brightky.serviceopsjava.requests.application.port.out;

import cm.brightky.serviceopsjava.requests.domain.Request;
import cm.brightky.serviceopsjava.requests.domain.RequestId;
import cm.brightky.serviceopsjava.requests.domain.RequestStatus;
import cm.brightky.serviceopsjava.users.domain.User;

import java.util.List;
import java.util.Optional;

public interface RequestRepository {
    public Request save(Request r);
    public Optional<Request> findById(RequestId id);

    Page search(User actor, String query, RequestStatus status, int page, int size);

    record Page(List<Request> items, long total) {}
}
