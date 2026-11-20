package cm.brightky.serviceopsjava.requests.application;

import cm.brightky.serviceopsjava.requests.application.port.out.RequestRepository;
import cm.brightky.serviceopsjava.requests.domain.Request;
import cm.brightky.serviceopsjava.requests.domain.RequestId;

import java.util.UUID;

public class CreateRequest {
    private final RequestRepository requestRepository;
    public CreateRequest(RequestRepository requestRepository) {
        this.requestRepository = requestRepository;
    }

//    choix de simplicite sinon ca aurait pu etre un dto
    public Request execute(String title) {
        var request = new Request(new RequestId(UUID.randomUUID()), title);

        return this.requestRepository.save(request);
    }
}
