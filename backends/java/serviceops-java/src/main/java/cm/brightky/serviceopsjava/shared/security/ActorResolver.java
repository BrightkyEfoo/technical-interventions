package cm.brightky.serviceopsjava.shared.security;

import cm.brightky.serviceopsjava.shared.error.ErrorCode;
import cm.brightky.serviceopsjava.shared.error.ServiceOpsException;
import cm.brightky.serviceopsjava.users.application.port.out.UserRepository;
import cm.brightky.serviceopsjava.users.domain.Role;
import cm.brightky.serviceopsjava.users.domain.User;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

@Component
public class ActorResolver {
    private final UserRepository users;
    private final boolean demo;

    ActorResolver(UserRepository users, Environment environment) {
        this.users = users;
        this.demo = environment.acceptsProfiles(Profiles.of("demo | memory"));
    }

    public boolean anonymousDemo(Jwt jwt, UUID demoId) {
        return jwt == null && demoId == null && demo;
    }

    public User require(Jwt jwt, UUID demoId) {
        User actor = resolve(jwt, demoId);
        if (actor.role() == Role.SUPER_ADMIN) {
            throw new ServiceOpsException(ErrorCode.REQUEST_FORBIDDEN, "The super admin has read-only access to requests");
        }
        return actor;
    }

    public User requireRead(Jwt jwt, UUID demoId) {
        return resolve(jwt, demoId);
    }

    public void requireSuperAdmin(Jwt jwt) {
        if (resolve(jwt, null).role() != Role.SUPER_ADMIN) {
            throw new ServiceOpsException(ErrorCode.REQUEST_FORBIDDEN, "Only a super admin can manage accounts");
        }
    }

    private User resolve(Jwt jwt, UUID demoId) {
        if (jwt != null) {
            Object realm = jwt.getClaims().get("realm_access");
            if (realm instanceof Map<?, ?> claims && claims.get("roles") instanceof Collection<?> roles) {
                for (Role role : Role.values()) {
                    if (roles.contains(role.name())) {
                        try {
                            return new User(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("preferred_username"), role);
                        } catch (IllegalArgumentException exception) {
                            throw unauthorized();
                        }
                    }
                }
            }
        } else if (demo && demoId != null) {
            return users.findById(demoId).orElseThrow(() -> new ServiceOpsException(ErrorCode.USER_NOT_FOUND, "Unknown demo user"));
        }
        throw unauthorized();
    }

    private ServiceOpsException unauthorized() {
        return new ServiceOpsException(ErrorCode.UNAUTHENTICATED, "An authenticated ServiceOps account is required");
    }
}
