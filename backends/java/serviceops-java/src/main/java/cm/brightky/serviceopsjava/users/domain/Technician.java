package cm.brightky.serviceopsjava.users.domain;

import java.util.UUID;

public class Technician extends User {
    public Technician(UUID id, String name) {
        super(id, name, Role.TECHNICIAN);
    }
}
