package cm.brightky.serviceopsjava.notifications;

import cm.brightky.serviceopsjava.shared.security.ActorResolver;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationsController {
    private final ActorResolver actors;
    private final RestClient client;
    private final String key;

    NotificationsController(ActorResolver actors, @Value("${SERVICEOPS_NOTIFICATIONS_URL:http://127.0.0.1:8091}") String url,
                            @Value("${SERVICEOPS_INTERNAL_KEY:}") String key) {
        this.actors = actors;
        this.key = key;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @GetMapping
    ResponseEntity<?> list(@RequestHeader(value = "X-Demo-Actor", required = false) UUID demoId,
                            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        var actor = actors.require(jwt, demoId);
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(client.get()
                    .uri("/internal/notifications/{id}", actor.id()).header("X-ServiceOps-Key", key)
                    .retrieve().body(String.class));
        } catch (RestClientException failure) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Notifications service is unavailable");
            problem.setProperty("code", "NOTIFICATIONS_UNAVAILABLE");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
        }
    }
}
