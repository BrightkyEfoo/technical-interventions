package cm.brightky.accounts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class KeycloakAccounts {
    private static final Logger LOG = LoggerFactory.getLogger(KeycloakAccounts.class);
    private static final String ADMIN = "/admin/realms/serviceops";
    private static final int PAGE_SIZE = 100;
    private static final ParameterizedTypeReference<List<DirectoryUser>> USER_LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<RealmRole>> ROLE_LIST = new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final String secret;

    public KeycloakAccounts(String url, String secret) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.secret = secret;
    }

    public boolean isConfigured() {
        return !secret.isBlank();
    }

    public StaffAccount createStaff(StaffRole role, String username, String email, String firstName,
                                    String lastName, String temporaryPassword) {
        String canonicalUsername = username.toLowerCase(Locale.ROOT);
        String canonicalEmail = email.toLowerCase(Locale.ROOT);
        String token = serviceToken();
        var roleMapping = call(() -> client.get().uri(ADMIN + "/roles/{name}", role.name())
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(RealmRole.class));
        var defaultMapping = call(() -> client.get().uri(ADMIN + "/roles/default-roles-serviceops")
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(RealmRole.class));
        if (roleMapping == null || defaultMapping == null) throw unavailable();

        String location = call(() -> client.post().uri(ADMIN + "/users")
                .headers(headers -> headers.setBearerAuth(token))
                .body(Map.of("username", canonicalUsername, "email", canonicalEmail, "firstName", firstName, "lastName", lastName,
                        "enabled", false, "credentials", List.of(new Credential("password", temporaryPassword, true))))
                .retrieve().toBodilessEntity().getHeaders().getFirst(HttpHeaders.LOCATION));
        UUID id = accountId(location);
        try {
            call(() -> client.post().uri(ADMIN + "/users/{id}/role-mappings/realm", id)
                    .headers(headers -> headers.setBearerAuth(token)).body(List.of(roleMapping)).retrieve().toBodilessEntity());
            call(() -> client.method(org.springframework.http.HttpMethod.DELETE)
                    .uri(ADMIN + "/users/{id}/role-mappings/realm", id)
                    .headers(headers -> headers.setBearerAuth(token)).body(List.of(defaultMapping)).retrieve().toBodilessEntity());
            call(() -> client.put().uri(ADMIN + "/users/{id}", id)
                    .headers(headers -> headers.setBearerAuth(token)).body(Map.of("enabled", true)).retrieve().toBodilessEntity());
            return new StaffAccount(id, canonicalUsername, canonicalEmail, role);
        } catch (DirectoryFailure failure) {
            rollback(id, token);
            throw failure;
        }
    }

    public AccountPage listAccounts(String query, int page, int size) {
        if (page < 0 || size < 1 || size > 50 || page > Integer.MAX_VALUE / size || query.length() > 120) {
            throw new DirectoryFailure(400, "INVALID_INPUT", "Vérifie la recherche et les paramètres de pagination.");
        }
        String token = serviceToken();
        String search = query.strip();
        var users = call(() -> client.get().uri(uri -> {
                    uri.path(ADMIN + "/users").queryParam("first", page * size).queryParam("max", size + 1);
                    if (search.isEmpty()) return uri.build();
                    return uri.queryParam("search", "{search}").build("*" + search + "*");
                })
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(USER_LIST));
        if (users == null) throw unavailable();
        var items = users.stream().limit(size).map(user -> {
            var roles = call(() -> realmRoles(user.id(), token));
            if (roles == null) throw unavailable();
            AccountRole role = List.of(AccountRole.values()).stream()
                    .filter(candidate -> roles.stream().anyMatch(mapping -> candidate.name().equals(mapping.name())))
                    .findFirst().orElse(null);
            return new AccountSummary(user.id(), user.username(), user.email(), user.firstName(), user.lastName(), role, user.enabled());
        }).toList();
        return new AccountPage(items, page, size, users.size() > size);
    }

    public List<TechnicianSummary> technicians() {
        String token = serviceToken();
        var technicians = new ArrayList<TechnicianSummary>();
        for (int first = 0; ; first += PAGE_SIZE) {
            int offset = first;
            var page = call(() -> client.get().uri(ADMIN + "/roles/TECHNICIAN/users?first={first}&max={max}", offset, PAGE_SIZE)
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().body(USER_LIST));
            if (page == null) throw unavailable();
            page.stream().filter(DirectoryUser::enabled)
                    .map(user -> new TechnicianSummary(user.id(), user.username())).forEach(technicians::add);
            if (page.size() < PAGE_SIZE) break;
        }
        return technicians.stream().sorted(Comparator.comparing(TechnicianSummary::name)).toList();
    }

    public Optional<TechnicianSummary> technician(UUID id) {
        String token = serviceToken();
        try {
            var user = client.get().uri(ADMIN + "/users/{id}", id)
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().body(DirectoryUser.class);
            if (user == null || !user.enabled()) return Optional.empty();
            var roles = realmRoles(id, token);
            if (roles == null) throw unavailable();
            if (roles.stream().noneMatch(role -> "TECHNICIAN".equals(role.name()))) {
                throw new DirectoryFailure(400, "INVALID_ASSIGNEE", "A request must be assigned to a technician");
            }
            return Optional.of(new TechnicianSummary(user.id(), user.username()));
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) return Optional.empty();
            throw unavailable();
        } catch (RestClientException failure) {
            throw unavailable();
        }
    }

    private List<RealmRole> realmRoles(UUID id, String token) {
        return client.get().uri(ADMIN + "/users/{id}/role-mappings/realm/composite", id)
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(ROLE_LIST);
    }

    private String serviceToken() {
        if (!isConfigured()) throw unavailable();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        var token = call(() -> client.post().uri("/realms/serviceops/protocol/openid-connect/token")
                .headers(headers -> headers.setBasicAuth("serviceops-accounts", secret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(ServiceToken.class));
        if (token == null || token.access_token() == null || token.access_token().isBlank()) throw unavailable();
        return token.access_token();
    }

    private UUID accountId(String location) {
        if (location == null) throw unavailable();
        try {
            return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        } catch (IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    private void rollback(UUID id, String token) {
        try {
            client.delete().uri(ADMIN + "/users/{id}", id)
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().toBodilessEntity();
        } catch (RestClientException failure) {
            LOG.warn("Keycloak staff provisioning could not be rolled back for account {}", id);
        }
    }

    private <T> T call(java.util.function.Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 409) {
                throw new DirectoryFailure(409, "ACCOUNT_CONFLICT", "Ce courriel ou cet identifiant existe déjà.");
            }
            if (failure.getStatusCode().value() == 400) {
                throw new DirectoryFailure(400, "INVALID_ACCOUNT", "Vérifie les informations et la politique de mot de passe.");
            }
            throw unavailable();
        } catch (RestClientException failure) {
            throw unavailable();
        }
    }

    private DirectoryFailure unavailable() {
        return new DirectoryFailure(503, "ACCOUNT_DIRECTORY_UNAVAILABLE", "Le service de gestion des comptes est indisponible.");
    }

    public enum StaffRole { MANAGER, TECHNICIAN }
    public enum AccountRole { SUPER_ADMIN, MANAGER, TECHNICIAN, REQUESTER }
    public record StaffAccount(UUID id, String username, String email, StaffRole role) {}
    public record AccountSummary(UUID id, String username, String email, String firstName, String lastName,
                                 AccountRole role, boolean enabled) {}
    public record AccountPage(List<AccountSummary> items, int page, int size, boolean hasNext) {}
    public record TechnicianSummary(UUID id, String name) {}
    private record RealmRole(String id, String name) {}
    private record DirectoryUser(UUID id, String username, boolean enabled, String email, String firstName, String lastName) {}
    private record ServiceToken(String access_token) {}
    private record Credential(String type, String value, boolean temporary) {
        @Override
        public String toString() { return "Credential[redacted]"; }
    }

    public static final class DirectoryFailure extends RuntimeException {
        private final int status;
        private final String code;

        private DirectoryFailure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }
        public String code() { return code; }
    }
}
