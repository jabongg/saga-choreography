package com.example.paymentservice.domain;

import com.example.contracts.InventoryReservedEvent;
import com.example.contracts.PaymentCompletedEvent;
import com.example.contracts.PaymentFailedEvent;
import com.example.contracts.TopicNames;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class PaymentService {

    private static final String SERVICE_NAME = "payment-service";

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final SagaControlService sagaControlService;

    public PaymentService(JdbcTemplate jdbcTemplate,
                          KafkaTemplate<String, Object> kafkaTemplate,
                          ObjectMapper objectMapper,
                          SagaControlService sagaControlService) {
        this.jdbcTemplate = jdbcTemplate;
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
    @KafkaListener(
            topics = TopicNames.INVENTORY_RESERVED,
            groupId = "payment-service",
            containerFactory = "inventoryReservedKafkaListenerContainerFactory"
    )
    public void onInventoryReserved(InventoryReservedEvent event,
                                    @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                                    @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {
        if (findByOrderId(event.orderId()) != null) {
            logSaga(event.orderId(), "payment.charge", key, TopicNames.INVENTORY_RESERVED, event, "SKIPPED", attempt(deliveryAttempt), "Duplicate inventory reservation ignored");
            return;
        }

        logSaga(event.orderId(), "payment.charge", key, TopicNames.INVENTORY_RESERVED, event, "STARTED", attempt(deliveryAttempt), null);
        sagaControlService.failIfConfigured("payment.charge.before-persist");

        String paymentId = UUID.randomUUID().toString();
        OffsetDateTime createdAt = OffsetDateTime.now();

        PaymentRecord paymentRecord = new PaymentRecord(
                paymentId,
                event.orderId(),
                event.userId(),
                event.recipientName(),
                event.recipientEmail(),
                event.cardDetails(),
                event.paymentMethod(),
                event.totalAmount(),
                "SUCCESS",
                createdAt
        );
        jdbcTemplate.update(
                """
                INSERT INTO payments
                (payment_id, order_id, user_id, recipient_name, recipient_email, card_details, payment_method, payment_amount, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                paymentRecord.paymentId(),
                paymentRecord.orderId(),
                paymentRecord.userId(),
                paymentRecord.recipientName(),
                paymentRecord.recipientEmail(),
                paymentRecord.cardDetails(),
                paymentRecord.paymentMethod(),
                paymentRecord.paymentAmount(),
                paymentRecord.status(),
                paymentRecord.createdAt()
        );

        sagaControlService.failIfConfigured("payment.charge.after-persist");
        sendEvent(
                TopicNames.PAYMENT_COMPLETED,
                paymentId,
                new PaymentCompletedEvent(
                        paymentId,
                        event.orderId(),
                        event.userId(),
                        event.recipientName(),
                        event.recipientEmail(),
                        event.cardDetails(),
                        event.paymentMethod(),
                        event.totalAmount(),
                        createdAt
                )
        );
        logSaga(event.orderId(), "payment.charge", paymentId, TopicNames.PAYMENT_COMPLETED, paymentRecord, "COMPLETED", attempt(deliveryAttempt), null);
    }

    @DltHandler
    public void onDlt(Object event,
                      @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                      @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic) {
        String sagaId = resolveSagaId(event);
        logSaga(sagaId, "payment.dlt", key, topic, event, "DLT", 0, "Message moved to dead-letter topic");

        if (event instanceof InventoryReservedEvent failedInventoryEvent && findByOrderId(failedInventoryEvent.orderId()) == null) {
            sendEvent(
                    TopicNames.PAYMENT_FAILED,
                    failedInventoryEvent.orderId(),
                    new PaymentFailedEvent(
                            failedInventoryEvent.orderId(),
                            failedInventoryEvent.cartId(),
                            failedInventoryEvent.userId(),
                            failedInventoryEvent.totalAmount(),
                            "Payment exhausted retries and moved to dead-letter topic",
                            OffsetDateTime.now()
                    )
            );
            logSaga(failedInventoryEvent.orderId(), "payment.fail", failedInventoryEvent.orderId(), TopicNames.PAYMENT_FAILED, failedInventoryEvent, "COMPLETED", 0, null);
        }
    }

    public List<PaymentRecord> findAll() {
        return new ArrayList<>(jdbcTemplate.query(
                """
                SELECT payment_id, order_id, user_id, recipient_name, recipient_email, card_details, payment_method, payment_amount, status, created_at
                FROM payments
                ORDER BY created_at DESC
                """,
                (rs, rowNum) -> new PaymentRecord(
                        rs.getString("payment_id"),
                        rs.getString("order_id"),
                        rs.getString("user_id"),
                        rs.getString("recipient_name"),
                        rs.getString("recipient_email"),
                        rs.getString("card_details"),
                        rs.getString("payment_method"),
                        rs.getBigDecimal("payment_amount"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class)
                )
        ));
    }

    public PaymentRecord get(String paymentId) {
        return jdbcTemplate.query(
                """
                SELECT payment_id, order_id, user_id, recipient_name, recipient_email, card_details, payment_method, payment_amount, status, created_at
                FROM payments
                WHERE payment_id = ?
                """,
                (rs, rowNum) -> new PaymentRecord(
                        rs.getString("payment_id"),
                        rs.getString("order_id"),
                        rs.getString("user_id"),
                        rs.getString("recipient_name"),
                        rs.getString("recipient_email"),
                        rs.getString("card_details"),
                        rs.getString("payment_method"),
                        rs.getBigDecimal("payment_amount"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                paymentId
        ).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown paymentId: " + paymentId));
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
            case TopicNames.INVENTORY_RESERVED -> readPayload(logRecord.payload(), InventoryReservedEvent.class);
            case TopicNames.PAYMENT_FAILED -> readPayload(logRecord.payload(), PaymentFailedEvent.class);
            default -> throw new IllegalArgumentException("Replay is not supported for topic: " + logRecord.topicName());
        };
        sendEvent(logRecord.topicName(), logRecord.messageKey(), replayEvent);
        logSaga(logRecord.sagaId(), "payment.replay", logRecord.messageKey(), logRecord.topicName(), replayEvent, "REPLAYED", logRecord.attempt(), null);
    }

    private PaymentRecord findByOrderId(String orderId) {
        return jdbcTemplate.query(
                """
                SELECT payment_id, order_id, user_id, recipient_name, recipient_email, card_details, payment_method, payment_amount, status, created_at
                FROM payments
                WHERE order_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new PaymentRecord(
                        rs.getString("payment_id"),
                        rs.getString("order_id"),
                        rs.getString("user_id"),
                        rs.getString("recipient_name"),
                        rs.getString("recipient_email"),
                        rs.getString("card_details"),
                        rs.getString("payment_method"),
                        rs.getBigDecimal("payment_amount"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                orderId
        ).stream().findFirst().orElse(null);
    }

    private int attempt(Integer deliveryAttempt) {
        return deliveryAttempt == null ? 1 : deliveryAttempt;
    }

    private String resolveSagaId(Object event) {
        if (event instanceof InventoryReservedEvent inventoryReservedEvent) {
            return inventoryReservedEvent.orderId();
        }
        if (event instanceof PaymentFailedEvent paymentFailedEvent) {
            return paymentFailedEvent.orderId();
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
                SERVICE_NAME,
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
            throw new IllegalStateException("Failed to publish payment event", exception);
        }
    }
}
