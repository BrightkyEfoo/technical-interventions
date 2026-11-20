package cm.brightky.serviceopsjava.notifications;

import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Profile("postgres & events")
public class AssignmentRelay {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AssignmentRelay.class);
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final String variant;

    AssignmentRelay(JdbcTemplate jdbc, RabbitTemplate rabbit, ObjectMapper json,
                    @Value("${serviceops.events.variant}") String variant) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.json = json;
        this.variant = variant;
    }

    @Scheduled(fixedDelayString = "${serviceops.events.relay-delay-ms:1000}")
    @Transactional
    public void publishPending() {
        var pending = jdbc.query("""
                SELECT id, request_id, request_version, recipient_id, created_at
                FROM request_outbox WHERE published_at IS NULL AND attempts < 10 AND next_attempt_at <= now()
                ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (row, n) -> new Assignment(row.getObject("id", UUID.class), "request.assigned", 1,
                row.getObject("request_id", UUID.class), row.getLong("request_version"),
                row.getObject("recipient_id", UUID.class), row.getObject("request_id", UUID.class),
                row.getObject("created_at", OffsetDateTime.class).toInstant().toString()));
        if (pending.isEmpty()) return;
        var event = pending.getFirst();
        try {
            var acknowledgement = new CorrelationData(event.eventId().toString());
            rabbit.convertAndSend("serviceops." + variant + ".events", "request.assigned",
                    json.writeValueAsString(event), acknowledgement);
            var confirmation = acknowledgement.getFuture().get(3, TimeUnit.SECONDS);
            if (!confirmation.isAck() || acknowledgement.getReturned() != null) {
                throw new IllegalStateException("Message not accepted by its queue");
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            jdbc.update("""
                    UPDATE request_outbox SET attempts = attempts + 1, last_error = ?,
                    next_attempt_at = clock_timestamp() + make_interval(secs => LEAST(120, 5 * (attempts + 1)))
                    WHERE id = ?
                    """, failure.getClass().getSimpleName(), event.eventId());
            LOG.warn("assignment_publication_failed eventId={} reason={}", event.eventId(), failure.getClass().getSimpleName());
            return;
        }
        jdbc.update("UPDATE request_outbox SET published_at = clock_timestamp(), last_error = NULL WHERE id = ?", event.eventId());
    }

    record Assignment(UUID eventId, String type, int schemaVersion, UUID requestId, long requestVersion,
                      UUID recipientId, UUID correlationId, String occurredAt) {}
}

@Configuration
@EnableScheduling
@Profile("postgres & events")
class AssignmentBroker {
    @Bean
    Declarables assignmentTopology(@Value("${serviceops.events.variant}") String variant) {
        String base = "serviceops." + variant;
        var exchange = new DirectExchange(base + ".events");
        var dead = new DirectExchange(base + ".dead");
        var queue = QueueBuilder.durable(base + ".assignments").deadLetterExchange(dead.getName()).build();
        var deadQueue = QueueBuilder.durable(base + ".diagnostics").build();
        return new Declarables(exchange, dead, queue, deadQueue,
                BindingBuilder.bind(queue).to(exchange).with("request.assigned"),
                BindingBuilder.bind(deadQueue).to(dead).with("request.assigned"));
    }
}
