package cm.brightky.serviceopsjava.users.infrastructure.configuration;

import cm.brightky.accounts.KeycloakAccounts;
import org.springframework.beans.factory.annotation.Value;

import cm.brightky.serviceopsjava.users.application.port.out.UserRepository;
import cm.brightky.serviceopsjava.users.domain.Manager;
import cm.brightky.serviceopsjava.users.domain.Technician;
import cm.brightky.serviceopsjava.users.domain.Role;
import cm.brightky.serviceopsjava.users.domain.User;
import cm.brightky.serviceopsjava.users.infrastructure.persistence.InMemoryUserRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.UUID;

@Configuration
public class UserConfiguration {
    @Bean
    KeycloakAccounts keycloakAccounts(@Value("${SERVICEOPS_ACCOUNTS_URL:http://localhost:8180}") String url,
                                      @Value("${SERVICEOPS_ACCOUNTS_CLIENT_SECRET:}") String secret) {
        return new KeycloakAccounts(url, secret);
    }
    @Bean
    public UserRepository userRepository() {
        return new InMemoryUserRepository();
    }

    @Bean
    @Profile("demo | postgres")
    public ApplicationRunner seedDemoUsers(UserRepository users) {
        return args -> {
            users.save(new Manager(UUID.fromString("00000000-0000-4000-8000-000000000001"), "Responsable démo"));
            users.save(new Technician(UUID.fromString("00000000-0000-4000-8000-000000000002"), "Technicien démo"));
            users.save(new User(UUID.fromString("00000000-0000-4000-8000-000000000003"), "Demandeur démo", Role.REQUESTER));
            users.save(new Technician(UUID.fromString("00000000-0000-4000-8000-000000000004"), "Technicien 2"));
        };
    }
}
