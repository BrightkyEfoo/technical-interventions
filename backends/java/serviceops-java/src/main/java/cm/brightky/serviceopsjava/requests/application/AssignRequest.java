package cm.brightky.serviceopsjava.requests.application;

import cm.brightky.serviceopsjava.requests.application.port.out.RequestRepository;
import cm.brightky.serviceopsjava.shared.error.ErrorCode;
import cm.brightky.serviceopsjava.shared.error.ServiceOpsException;
import cm.brightky.serviceopsjava.users.application.port.out.UserRepository;

public class AssignRequest {
    private final RequestRepository requestRepository;
    private final UserRepository userRepository;

    public AssignRequest(RequestRepository requestRepository, UserRepository userRepository) {
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
    }

    public void execute(AssignRequestCommand command) {
        var request = requestRepository.findById(command.requestId())
                .orElseThrow(() -> new ServiceOpsException(
                        ErrorCode.REQUEST_NOT_FOUND, "Request not found: " + command.requestId().value()));
        var actor = userRepository.findById(command.actorId())
                .orElseThrow(() -> new ServiceOpsException(
                        ErrorCode.USER_NOT_FOUND, "Actor not found: " + command.actorId()));
        var technician = userRepository.findById(command.technicianId())
                .orElseThrow(() -> new ServiceOpsException(
                        ErrorCode.USER_NOT_FOUND, "Technician not found: " + command.technicianId()));

        request.assignTo(actor, technician);
        requestRepository.save(request);
    }
}
