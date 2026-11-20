package cm.brightky.serviceopsjava.users.application.port.out;

import cm.brightky.serviceopsjava.users.domain.User;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface UserRepository {
    public User save(User user);
    public Optional<User> findById(UUID id);
    List<User> findTechnicians();
}
