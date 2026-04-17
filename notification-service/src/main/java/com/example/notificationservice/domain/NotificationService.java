package com.example.notificationservice.domain;

import com.example.contracts.NotificationFailedEvent;
import com.example.contracts.PaymentCompletedEvent;
import com.example.contracts.TopicNames;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.notificationservice.template.NotificationTemplateProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.transaction.annotation.Transactional;

import java.text.MessageFormat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class NotificationService {

    private final JdbcTemplate jdbcTemplate;
    private final JavaMailSender mailSender;
    private final NotificationTemplateProperties templateProperties;
    private final String fromAddress;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final SagaControlService sagaControlService;

    public NotificationService(JdbcTemplate jdbcTemplate,
                               JavaMailSender mailSender,
                               NotificationTemplateProperties templateProperties,
                               @Value("${app.mail.from:}") String fromAddress,
                               KafkaTemplate<String, Object> kafkaTemplate,
                               ObjectMapper objectMapper,
                               SagaControlService sagaControlService) {
        this.jdbcTemplate = jdbcTemplate;
        this.mailSender = mailSender;
        this.templateProperties = templateProperties;
        this.fromAddress = fromAddress;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.sagaControlService = sagaControlService;
    }

    @Transactional
    @RetryableTopic(
            attempts = "${app.kafka.retry.attempts:4}",
            backoff = @Backoff(
                    delayExpression = "${app.kafka.retry.initial-delay-ms:1000}",
                    multiplierExpression = "${app.kafka.retry.multiplier:2.0}"
            ),
            autoCreateTopics = "true"
    )
    @KafkaListener(topics = TopicNames.PAYMENT_COMPLETED, groupId = "notification-service")
    public void onPaymentCompleted(PaymentCompletedEvent event,
                                   @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                                   @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {
        if (findByPaymentId(event.paymentId()) != null) {
            logSaga(event.orderId(), "notification.send", key, TopicNames.PAYMENT_COMPLETED, event, "SKIPPED", attempt(deliveryAttempt), "Duplicate payment completion ignored");
            return;
        }

        logSaga(event.orderId(), "notification.send", key, TopicNames.PAYMENT_COMPLETED, event, "STARTED", attempt(deliveryAttempt), null);
        sagaControlService.failIfConfigured("notification.send.before-email");

        String notificationId = UUID.randomUUID().toString();
        String message = MessageFormat.format(
                templateProperties.paymentBody(),
                event.recipientName(),
                event.cardDetails(),
                event.paymentAmount()
        );

        SimpleMailMessage mail = new SimpleMailMessage();
        if (fromAddress != null && !fromAddress.isBlank()) {
            mail.setFrom(fromAddress);
        }
        mail.setTo(event.recipientEmail());
        mail.setSubject(templateProperties.paymentSubject());
        mail.setText(message);
        mailSender.send(mail);
        sagaControlService.failIfConfigured("notification.send.after-email");

        NotificationRecord record = new NotificationRecord(
                notificationId,
                event.paymentId(),
                event.orderId(),
                event.recipientName(),
                event.recipientEmail(),
                "EMAIL",
                message,
                "SENT",
                OffsetDateTime.now()
        );
        jdbcTemplate.update(
                """
                INSERT INTO notifications
                (notification_id, payment_id, order_id, recipient_name, recipient_email, channel, message, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.notificationId(),
                record.paymentId(),
                record.orderId(),
                record.recipientName(),
                record.recipientEmail(),
                record.channel(),
                record.message(),
                record.status(),
                record.createdAt()
        );
        sagaControlService.failIfConfigured("notification.send.after-persist");
        logSaga(event.orderId(), "notification.send", event.paymentId(), TopicNames.PAYMENT_COMPLETED, record, "COMPLETED", attempt(deliveryAttempt), null);
    }

    @DltHandler // dead letter topic (like dlq dead letter queue)
    public void onDlt(Object event,
                      @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                      @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic) {
        logSaga(resolveSagaId(event), "notification.dlt", key, topic, event, "DLT", 0, "Notification moved to dead-letter topic");
        if (event instanceof PaymentCompletedEvent paymentCompletedEvent) {
            sendEvent(
                    TopicNames.NOTIFICATION_FAILED,
                    paymentCompletedEvent.paymentId(),
                    new NotificationFailedEvent(
                            paymentCompletedEvent.paymentId(),
                            paymentCompletedEvent.orderId(),
                            paymentCompletedEvent.userId(),
                            paymentCompletedEvent.paymentAmount(),
                            "Notification exhausted retries and moved to dead-letter topic",
                            OffsetDateTime.now()
                    )
            );
        }
    }

    public List<NotificationRecord> findAll() {
        return new ArrayList<>(jdbcTemplate.query(
                """
                SELECT notification_id, payment_id, order_id, recipient_name, recipient_email, channel, message, status, created_at
                FROM notifications
                ORDER BY created_at DESC
                """,
                (rs, rowNum) -> new NotificationRecord(
                        rs.getString("notification_id"),
                        rs.getString("payment_id"),
                        rs.getString("order_id"),
                        rs.getString("recipient_name"),
                        rs.getString("recipient_email"),
                        rs.getString("channel"),
                        rs.getString("message"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class)
                )
        ));
    }

    public List<SagaLogRecord> findSagaLogs() {
        return jdbcTemplate.query(
                """
                SELECT id, saga_id, step_name, service_name, message_key, topic_name, payload::text AS payload,
                       status, attempt, error_message, created_at
                FROM saga_log
                ORDER BY created_at DESC, id DESC
                """,
                (rs, rowNum) -> new SagaLogRecord(
                        rs.getLong("id"),
                        rs.getString("saga_id"),
                        rs.getString("step_name"),
                        rs.getString("service_name"),
                        rs.getString("message_key"),
                        rs.getString("topic_name"),
                        rs.getString("payload"),
                        rs.getString("status"),
                        rs.getInt("attempt"),
                        rs.getString("error_message"),
                        rs.getObject("created_at", OffsetDateTime.class)
                )
        );
    }

    public void replaySagaLog(Long logId) {
        SagaLogRecord logRecord = jdbcTemplate.query(
                """
                SELECT id, saga_id, step_name, service_name, message_key, topic_name, payload::text AS payload,
                       status, attempt, error_message, created_at
                FROM saga_log
                WHERE id = ?
                """,
                (rs, rowNum) -> new SagaLogRecord(
                        rs.getLong("id"),
                        rs.getString("saga_id"),
                        rs.getString("step_name"),
                        rs.getString("service_name"),
                        rs.getString("message_key"),
                        rs.getString("topic_name"),
                        rs.getString("payload"),
                        rs.getString("status"),
                        rs.getInt("attempt"),
                        rs.getString("error_message"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                logId
        ).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown saga log id: " + logId));

        Object replayEvent = switch (logRecord.topicName()) {
            case TopicNames.PAYMENT_COMPLETED -> readPayload(logRecord.payload(), PaymentCompletedEvent.class);
            default -> throw new IllegalArgumentException("Replay is not supported for topic: " + logRecord.topicName());
        };
        sendEvent(logRecord.topicName(), logRecord.messageKey(), replayEvent);
        logSaga(logRecord.sagaId(), "notification.replay", logRecord.messageKey(), logRecord.topicName(), replayEvent, "REPLAYED", logRecord.attempt(), null);
    }

    private NotificationRecord findByPaymentId(String paymentId) {
        return jdbcTemplate.query(
                """
                SELECT notification_id, payment_id, order_id, recipient_name, recipient_email, channel, message, status, created_at
                FROM notifications
                WHERE payment_id = ?
                """,
                (rs, rowNum) -> new NotificationRecord(
                        rs.getString("notification_id"),
                        rs.getString("payment_id"),
                        rs.getString("order_id"),
                        rs.getString("recipient_name"),
                        rs.getString("recipient_email"),
                        rs.getString("channel"),
                        rs.getString("message"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                paymentId
        ).stream().findFirst().orElse(null);
    }

    private int attempt(Integer deliveryAttempt) {
        return deliveryAttempt == null ? 1 : deliveryAttempt;
    }

    private String resolveSagaId(Object event) {
        if (event instanceof PaymentCompletedEvent paymentCompletedEvent) {
            return paymentCompletedEvent.orderId();
        }
        return "unknown";
    }

    private void logSaga(String sagaId,
                         String stepName,
                         String key,
                         String topic,
                         Object payload,
                         String status,
                         int attempt,
                         String errorMessage) {
        jdbcTemplate.update(
                """
                INSERT INTO saga_log
                (saga_id, step_name, service_name, message_key, topic_name, payload, status, attempt, error_message, created_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                """,
                sagaId,
                stepName,
                "notification-service",
                key,
                topic,
                writePayload(payload),
                status,
                attempt,
                errorMessage,
                OffsetDateTime.now()
        );
    }

    private String writePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize saga payload", exception);
        }
    }

    private <T> T readPayload(String payload, Class<T> payloadType) {
        try {
            return objectMapper.readValue(payload, payloadType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize saga payload", exception);
        }
    }

    private void sendEvent(String topic, String key, Object event) {
        try {
            kafkaTemplate.send(topic, key, event).get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to publish notification event", exception);
        }
    }
}
