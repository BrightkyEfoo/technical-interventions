package cm.brightky.serviceopsnotifications

import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.amqp.core.*
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootApplication
class NotificationsApplication {
    @Bean
    fun assignmentTopology(@Value("\${serviceops.events.variant}") variant: String): Declarables {
        val base = "serviceops.$variant"
        val exchange = DirectExchange("$base.events")
        val dead = DirectExchange("$base.dead")
        val inbox = QueueBuilder.durable("$base.assignments").deadLetterExchange(dead.name).build()
        val diagnostics = QueueBuilder.durable("$base.diagnostics").build()
        return Declarables(exchange, dead, inbox, diagnostics, BindingBuilder.bind(inbox).to(exchange).with("request.assigned"),
            BindingBuilder.bind(diagnostics).to(dead).with("request.assigned"))
    }
}

fun main(args: Array<String>) {
    runApplication<NotificationsApplication>(*args)
}

data class Assignment(val eventId: UUID, val type: String, val schemaVersion: Int, val requestId: UUID,
                      val requestVersion: Long, val recipientId: UUID, val correlationId: UUID, val occurredAt: OffsetDateTime)
data class Notification(val id: UUID, val requestId: UUID, val createdAt: String)

@Service
class Notifications(private val database: JdbcTemplate, private val json: ObjectMapper) {
    @RabbitListener(queues = ["serviceops.\${serviceops.events.variant}.assignments"])
    @Transactional
    fun receive(payload: String) {
        val assignment = try {
            json.readValue(payload, Assignment::class.java).also {
                require(it.type == "request.assigned" && it.schemaVersion == 1 && it.requestVersion >= 0)
            }
        } catch (invalid: RuntimeException) {
            throw AmqpRejectAndDontRequeueException("Invalid request.assigned v1", invalid)
        }
        database.update("""
            INSERT INTO notifications(event_id, request_id, request_version, recipient_id, created_at)
            VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
        """, assignment.eventId, assignment.requestId, assignment.requestVersion, assignment.recipientId, assignment.occurredAt)
    }

    fun find(recipient: UUID) = database.query("""
        SELECT event_id, request_id, created_at
        FROM notifications WHERE recipient_id = ? ORDER BY created_at DESC, event_id LIMIT 100
    """, { row, _ ->
        Notification(row.getObject(1, UUID::class.java), row.getObject(2, UUID::class.java),
            row.getObject(3, OffsetDateTime::class.java).toInstant().toString())
    }, recipient)
}

@RestController
class NotificationQueries(private val notifications: Notifications, @Value("\${SERVICEOPS_INTERNAL_KEY}") secret: String) {
    private val key = secret.toByteArray()

    init {
        require(secret.length >= 20) { "SERVICEOPS_INTERNAL_KEY is too short" }
    }

    @GetMapping("/internal/notifications/{recipient}")
    fun list(@PathVariable recipient: UUID, @RequestHeader(value = "X-ServiceOps-Key", defaultValue = "") supplied: String): List<Notification> {
        if (!MessageDigest.isEqual(key, supplied.toByteArray())) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }
        return notifications.find(recipient)
    }
}
