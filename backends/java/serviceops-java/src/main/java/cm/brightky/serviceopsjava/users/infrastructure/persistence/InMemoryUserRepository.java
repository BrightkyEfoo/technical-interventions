package cm.brightky.serviceopsjava.users.infrastructure.persistence;

import cm.brightky.serviceopsjava.users.application.port.out.UserRepository;
import cm.brightky.serviceopsjava.users.domain.User;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public class InMemoryUserRepository implements UserRepository {
    @Override
    public List<User> findTechnicians() {
        return users.values().stream()
                .filter(user -> user.role() == cm.brightky.serviceopsjava.users.domain.Role.TECHNICIAN)
                .sorted(java.util.Comparator.comparing(User::name))
                .toList();
    }
    private final ConcurrentHashMap<UUID, User> users = new ConcurrentHashMap<>();
    @Override
    public User save(User user) {
        this.users.put(user.id(), user);
        return this.users.get(user.id());
    }

    @Override
    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(this.users.get(id));
    }
}
