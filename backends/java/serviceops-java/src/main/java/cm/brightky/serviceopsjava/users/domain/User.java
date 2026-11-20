package cm.brightky.serviceopsjava.users.domain;

import java.util.Objects;
import java.util.UUID;

public class User {
    private final UUID id;
    private final String name;
    private final Role role;

    public User(UUID id, String name, Role role) {
        this.id = Objects.requireNonNull(id, "User ID is required");
        this.name = name;
        this.role = Objects.requireNonNull(role, "User role is required");
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Role role() {
        return role;
    }
}
