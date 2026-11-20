package cm.brightky.serviceopsjava.users.domain;

import cm.brightky.serviceopsjava.requests.domain.Request;

import java.util.UUID;

public class Manager extends User {
    public Manager(UUID id, String name) {
        super(id, name, Role.MANAGER);
    }

    public void assignRequest(Request request, Technician technician) {
        request.assignTo(this, technician);
    }
}
