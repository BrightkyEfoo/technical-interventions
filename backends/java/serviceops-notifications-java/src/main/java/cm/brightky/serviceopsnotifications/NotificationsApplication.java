package cm.brightky.serviceopsnotifications;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@SpringBootApplication
public class NotificationsApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationsApplication.class, args);
    }

    @Bean
    Declarables assignmentTopology(@Value("${serviceops.events.variant}") String variant) {
        var base = "serviceops." + variant;
        var source = new DirectExchange(base + ".events");
        var dead = new DirectExchange(base + ".dead");
        var inbox = QueueBuilder.durable(base + ".assignments").deadLetterExchange(dead.getName()).build();
        var diagnostics = QueueBuilder.durable(base + ".diagnostics").build();
        return new Declarables(source, dead, inbox, diagnostics,
                BindingBuilder.bind(inbox).to(source).with("request.assigned"),
                BindingBuilder.bind(diagnostics).to(dead).with("request.assigned"));
    }
}

@Service
class Notifications {
    private final JdbcTemplate database;
    private final ObjectMapper json;

    Notifications(JdbcTemplate database, ObjectMapper json) {
        this.database = database;
        this.json = json;
    }

    @RabbitListener(queues = "serviceops.${serviceops.events.variant}.assignments")
    @Transactional
    public void receive(String payload) {
        Assignment assignment;
        OffsetDateTime created;
        try {
            assignment = json.readValue(payload, Assignment.class);
            created = OffsetDateTime.parse(assignment.occurredAt());
            if (!"request.assigned".equals(assignment.type()) || assignment.schemaVersion() != 1 ||
                    assignment.eventId() == null || assignment.requestId() == null || assignment.recipientId() == null ||
                    assignment.correlationId() == null || assignment.requestVersion() < 0) {
                throw new IllegalArgumentException("Invalid assignment envelope");
            }
        } catch (RuntimeException invalid) {
            throw new AmqpRejectAndDontRequeueException("Invalid request.assigned v1", invalid);
        }
        database.update("""
                INSERT INTO notifications(event_id, request_id, request_version, recipient_id, created_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, assignment.eventId(), assignment.requestId(), assignment.requestVersion(), assignment.recipientId(), created);
    }

    List<Notification> find(UUID recipient) {
        return database.query("""
                SELECT event_id, request_id, created_at
                FROM notifications WHERE recipient_id = ? ORDER BY created_at DESC, event_id LIMIT 100
                """, (row, index) -> new Notification(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getObject(3, OffsetDateTime.class).toInstant().toString()), recipient);
    }

    record Assignment(UUID eventId, String type, int schemaVersion, UUID requestId, long requestVersion,
                      UUID recipientId, UUID correlationId, String occurredAt) {}
    record Notification(UUID id, UUID requestId, String createdAt) {}
}

@RestController
class NotificationQueries {
    private final Notifications notifications;
    private final byte[] key;

    NotificationQueries(Notifications notifications, @Value("${SERVICEOPS_INTERNAL_KEY}") String key) {
        if (key.length() < 20) throw new IllegalArgumentException("SERVICEOPS_INTERNAL_KEY is too short");
        this.notifications = notifications;
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/notifications/{recipient}")
    List<Notifications.Notification> list(@PathVariable UUID recipient, @RequestHeader(value = "X-ServiceOps-Key", defaultValue = "") String supplied) {
        if (!MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return notifications.find(recipient);
    }
}
