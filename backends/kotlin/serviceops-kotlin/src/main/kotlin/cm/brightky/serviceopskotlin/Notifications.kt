package cm.brightky.serviceopskotlin

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AssignmentEvent(
    val eventId: UUID, val requestId: UUID, val requestVersion: Long, val recipientId: UUID, val occurredAt: String,
    val correlationId: UUID = requestId, val type: String = "request.assigned", val schemaVersion: Int = 1,
)

@Configuration
@EnableScheduling
@Profile("postgres & events")
class AssignmentBroker {
    @Bean
    fun topology(@Value("\${serviceops.events.variant}") variant: String): Declarables {
        val base = "serviceops.$variant"
        val exchange = DirectExchange("$base.events")
        val dead = DirectExchange("$base.dead")
        val queue = QueueBuilder.durable("$base.assignments").deadLetterExchange(dead.name).build()
        val diagnostic = QueueBuilder.durable("$base.diagnostics").build()
        return Declarables(exchange, dead, queue, diagnostic, BindingBuilder.bind(queue).to(exchange).with("request.assigned"),
            BindingBuilder.bind(diagnostic).to(dead).with("request.assigned"))
    }
}

@Component
@Profile("postgres & events")
class AssignmentRelay(
    private val jdbc: JdbcTemplate, private val rabbit: RabbitTemplate, private val json: ObjectMapper,
    @Value("\${serviceops.events.variant}") private val variant: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${serviceops.events.relay-delay-ms:1000}")
    @Transactional
    fun publishPending() {
        val pending = jdbc.query("""
            SELECT id, request_id, request_version, recipient_id, created_at
            FROM request_outbox WHERE published_at IS NULL AND attempts < 10 AND next_attempt_at <= now()
            ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
        """, { row, _ ->
            AssignmentEvent(row.getObject("id", UUID::class.java), row.getObject("request_id", UUID::class.java),
                row.getLong("request_version"), row.getObject("recipient_id", UUID::class.java),
                row.getObject("created_at", OffsetDateTime::class.java).toInstant().toString())
        }).firstOrNull() ?: return
        try {
            val handle = CorrelationData(pending.eventId.toString())
            rabbit.convertAndSend("serviceops.$variant.events", "request.assigned", json.writeValueAsString(pending), handle)
            if (!handle.future.get(3, TimeUnit.SECONDS).isAck || handle.returned != null)
                error("Message not accepted by its queue")
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            jdbc.update("""
                UPDATE request_outbox SET attempts = attempts + 1, last_error = ?,
                next_attempt_at = clock_timestamp() + make_interval(secs => LEAST(120, 5 * (attempts + 1))) WHERE id = ?
            """, failure.javaClass.simpleName, pending.eventId)
            logger.warn("assignment_publication_failed eventId={} reason={}", pending.eventId, failure.javaClass.simpleName)
            return
        }
        jdbc.update("UPDATE request_outbox SET published_at = clock_timestamp(), last_error = NULL WHERE id = ?", pending.eventId)
    }
}

@RestController
class NotificationsController(
    private val actors: Actors,
    @Value("\${SERVICEOPS_NOTIFICATIONS_URL:http://127.0.0.1:8092}") url: String,
    @Value("\${SERVICEOPS_INTERNAL_KEY:}") private val key: String,
) {
    private val client = RestClient.builder().baseUrl(url).requestFactory(
        JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()).apply {
            setReadTimeout(Duration.ofSeconds(3))
        }
    ).build()

    @GetMapping("/api/v1/notifications")
    fun list(request: HttpServletRequest): ResponseEntity<*> {
        val actor = actors.current(request)
        return try {
            val data = client.get().uri("/internal/notifications/{id}", actor.id).header("X-ServiceOps-Key", key)
                .retrieve().body(String::class.java)
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(data)
        } catch (_: RestClientException) {
            val problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Notifications service is unavailable")
            problem.setProperty("code", "NOTIFICATIONS_UNAVAILABLE")
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
        }
    }
}
