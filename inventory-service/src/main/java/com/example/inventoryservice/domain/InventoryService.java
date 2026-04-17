package com.example.inventoryservice.domain;

import com.example.contracts.CartItem;
import com.example.contracts.InventoryFailedEvent;
import com.example.contracts.InventoryReleasedEvent;
import com.example.contracts.InventoryReservedEvent;
import com.example.contracts.OrderCreatedEvent;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class InventoryService {

    private static final String SERVICE_NAME = "inventory-service";

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final SagaControlService sagaControlService;

    public InventoryService(JdbcTemplate jdbcTemplate,
                            KafkaTemplate<String, Object> kafkaTemplate,
                            ObjectMapper objectMapper,
                            SagaControlService sagaControlService) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.sagaControlService = sagaControlService;
    }

    public InventoryStockRecord upsertStock(String productId, UpsertInventoryRequest request) {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId is required");
        }
        if (request == null || request.totalQuantity() == null || request.totalQuantity() < 0) {
            throw new IllegalArgumentException("totalQuantity must be zero or greater");
        }
        if (request.productName() == null || request.productName().isBlank()) {
            throw new IllegalArgumentException("productName is required");
        }

        jdbcTemplate.update(
                """
                INSERT INTO inventory_stock (product_id, product_name, total_qty, reserved_qty, updated_at)
                VALUES (?, ?, ?, 0, ?)
                ON CONFLICT (product_id)
                DO UPDATE SET product_name = EXCLUDED.product_name,
                              total_qty = EXCLUDED.total_qty,
                              updated_at = EXCLUDED.updated_at
                """,
                productId.trim(),
                request.productName().trim(),
                request.totalQuantity(),
                OffsetDateTime.now()
        );
        return findStock(productId);
    }

    public List<InventoryStockRecord> findAllStock() {
        return jdbcTemplate.query(
                """
                SELECT product_id, product_name, total_qty, reserved_qty, updated_at
                FROM inventory_stock
                ORDER BY product_id
                """,
                (rs, rowNum) -> new InventoryStockRecord(
                        rs.getString("product_id"),
                        rs.getString("product_name"),
                        rs.getInt("total_qty"),
                        rs.getInt("reserved_qty"),
                        rs.getInt("total_qty") - rs.getInt("reserved_qty"),
                        rs.getObject("updated_at", OffsetDateTime.class)
                )
        );
    }

    public List<InventoryReservationRecord> findReservations() {
        return jdbcTemplate.query(
                """
                SELECT reservation_id, order_id, cart_id, user_id, status, total_amount, reason, created_at, updated_at
                FROM inventory_reservations
                ORDER BY updated_at DESC
                """,
                (rs, rowNum) -> new InventoryReservationRecord(
                        rs.getString("reservation_id"),
                        rs.getString("order_id"),
                        rs.getString("cart_id"),
                        rs.getString("user_id"),
                        rs.getString("status"),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("reason"),
                        fetchReservationItems(rs.getString("reservation_id")),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                )
        );
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
    @KafkaListener(topics = TopicNames.ORDER_CREATED, groupId = "inventory-service", containerFactory = "orderCreatedKafkaListenerContainerFactory")
    public void onOrderCreated(OrderCreatedEvent event,
                               @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                               @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {
        InventoryReservationRecord existing = findReservationByOrderId(event.orderId());
        if (existing != null && !"FAILED".equals(existing.status())) {
            logSaga(event.orderId(), "inventory.reserve", key, TopicNames.ORDER_CREATED, event, "SKIPPED", attempt(deliveryAttempt), "Duplicate order event ignored");
            return;
        }

        logSaga(event.orderId(), "inventory.reserve", key, TopicNames.ORDER_CREATED, event, "STARTED", attempt(deliveryAttempt), null);
        sagaControlService.failIfConfigured("inventory.reserve.before-check");

        String availabilityError = firstAvailabilityError(event.items());
        if (availabilityError != null) {
            sendEvent(
                    TopicNames.INVENTORY_FAILED,
                    event.orderId(),
                    new InventoryFailedEvent(
                            event.orderId(),
                            event.cartId(),
                            event.userId(),
                            event.items(),
                            event.totalAmount(),
                            availabilityError,
                            OffsetDateTime.now()
                    )
            );
            logSaga(event.orderId(), "inventory.reserve", event.orderId(), TopicNames.INVENTORY_FAILED, event, "COMPLETED", attempt(deliveryAttempt), availabilityError);
            return;
        }

        String reservationId = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                """
                INSERT INTO inventory_reservations (reservation_id, order_id, cart_id, user_id, status, total_amount, reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                reservationId,
                event.orderId(),
                event.cartId(),
                event.userId(),
                "RESERVED",
                event.totalAmount(),
                null,
                now,
                now
        );
        for (CartItem item : event.items()) {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE inventory_stock
                    SET reserved_qty = reserved_qty + ?, updated_at = ?
                    WHERE product_id = ? AND total_qty - reserved_qty >= ?
                    """,
                    item.quantity(),
                    now,
                    item.productId(),
                    item.quantity()
            );
            if (updated == 0) {
                throw new IllegalStateException("Inventory changed during reservation for productId=" + item.productId());
            }
            jdbcTemplate.update(
                    """
                    INSERT INTO inventory_reservation_items (reservation_id, product_id, product_name, quantity, unit_price)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    reservationId,
                    item.productId(),
                    item.productName(),
                    item.quantity(),
                    item.unitPrice()
            );
        }
        sagaControlService.failIfConfigured("inventory.reserve.after-persist");
        sendEvent(
                TopicNames.INVENTORY_RESERVED,
                event.orderId(),
                new InventoryReservedEvent(
                        event.orderId(),
                        event.cartId(),
                        event.userId(),
                        event.recipientName(),
                        event.recipientEmail(),
                        event.cardDetails(),
                        event.paymentMethod(),
                        event.items(),
                        event.totalAmount(),
                        now
                )
        );
        logSaga(event.orderId(), "inventory.reserve", event.orderId(), TopicNames.INVENTORY_RESERVED, event, "COMPLETED", attempt(deliveryAttempt), null);
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
    @KafkaListener(topics = TopicNames.PAYMENT_FAILED, groupId = "inventory-service", containerFactory = "paymentFailedKafkaListenerContainerFactory")
    public void onPaymentFailed(PaymentFailedEvent event,
                                @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                                @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {
        InventoryReservationRecord reservation = findReservationByOrderId(event.orderId());
        if (reservation == null || "RELEASED".equals(reservation.status())) {
            logSaga(event.orderId(), "inventory.release", key, TopicNames.PAYMENT_FAILED, event, "SKIPPED", attempt(deliveryAttempt), "No releasable reservation found");
            return;
        }
        if ("CONFIRMED".equals(reservation.status())) {
            logSaga(event.orderId(), "inventory.release", key, TopicNames.PAYMENT_FAILED, event, "SKIPPED", attempt(deliveryAttempt), "Inventory already confirmed");
            return;
        }

        logSaga(event.orderId(), "inventory.release", key, TopicNames.PAYMENT_FAILED, event, "STARTED", attempt(deliveryAttempt), null);
        sagaControlService.failIfConfigured("inventory.release.before-update");
        OffsetDateTime now = OffsetDateTime.now();
        for (CartItem item : reservation.items()) {
            jdbcTemplate.update(
                    "UPDATE inventory_stock SET reserved_qty = reserved_qty - ?, updated_at = ? WHERE product_id = ?",
                    item.quantity(),
                    now,
                    item.productId()
            );
        }
        jdbcTemplate.update(
                "UPDATE inventory_reservations SET status = ?, reason = ?, updated_at = ? WHERE reservation_id = ?",
                "RELEASED",
                event.reason(),
                now,
                reservation.reservationId()
        );
        sendEvent(
                TopicNames.INVENTORY_RELEASED,
                event.orderId(),
                new InventoryReleasedEvent(
                        reservation.orderId(),
                        reservation.cartId(),
                        reservation.userId(),
                        reservation.items(),
                        reservation.totalAmount(),
                        event.reason(),
                        now
                )
        );
        logSaga(event.orderId(), "inventory.release", event.orderId(), TopicNames.INVENTORY_RELEASED, reservation, "COMPLETED", attempt(deliveryAttempt), null);
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
    @KafkaListener(topics = TopicNames.PAYMENT_COMPLETED, groupId = "inventory-service", containerFactory = "paymentCompletedKafkaListenerContainerFactory")
    public void onPaymentCompleted(PaymentCompletedEvent event,
                                   @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                                   @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {
        InventoryReservationRecord reservation = findReservationByOrderId(event.orderId());
        if (reservation == null || "CONFIRMED".equals(reservation.status())) {
            logSaga(event.orderId(), "inventory.confirm", key, TopicNames.PAYMENT_COMPLETED, event, "SKIPPED", attempt(deliveryAttempt), "Reservation already confirmed or missing");
            return;
        }
        if ("RELEASED".equals(reservation.status())) {
            logSaga(event.orderId(), "inventory.confirm", key, TopicNames.PAYMENT_COMPLETED, event, "SKIPPED", attempt(deliveryAttempt), "Released reservation ignored");
            return;
        }

        logSaga(event.orderId(), "inventory.confirm", key, TopicNames.PAYMENT_COMPLETED, event, "STARTED", attempt(deliveryAttempt), null);
        sagaControlService.failIfConfigured("inventory.confirm.before-update");
        OffsetDateTime now = OffsetDateTime.now();
        for (CartItem item : reservation.items()) {
            jdbcTemplate.update(
                    "UPDATE inventory_stock SET total_qty = total_qty - ?, reserved_qty = reserved_qty - ?, updated_at = ? WHERE product_id = ?",
                    item.quantity(),
                    item.quantity(),
                    now,
                    item.productId()
            );
        }
        jdbcTemplate.update(
                "UPDATE inventory_reservations SET status = ?, updated_at = ? WHERE reservation_id = ?",
                "CONFIRMED",
                now,
                reservation.reservationId()
        );
        logSaga(event.orderId(), "inventory.confirm", key, TopicNames.PAYMENT_COMPLETED, event, "COMPLETED", attempt(deliveryAttempt), null);
    }

    @DltHandler
    public void onDlt(Object event,
                      @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                      @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic) {
        logSaga(resolveSagaId(event), "inventory.dlt", key, topic, event, "DLT", 0, "Inventory message moved to dead-letter topic");
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
            case TopicNames.ORDER_CREATED -> readPayload(logRecord.payload(), OrderCreatedEvent.class);
            case TopicNames.PAYMENT_FAILED -> readPayload(logRecord.payload(), PaymentFailedEvent.class);
            case TopicNames.PAYMENT_COMPLETED -> readPayload(logRecord.payload(), PaymentCompletedEvent.class);
            default -> throw new IllegalArgumentException("Replay is not supported for topic: " + logRecord.topicName());
        };
        sendEvent(logRecord.topicName(), logRecord.messageKey(), replayEvent);
        logSaga(logRecord.sagaId(), "inventory.replay", logRecord.messageKey(), logRecord.topicName(), replayEvent, "REPLAYED", logRecord.attempt(), null);
    }

    private InventoryStockRecord findStock(String productId) {
        return jdbcTemplate.query(
                """
                SELECT product_id, product_name, total_qty, reserved_qty, updated_at
                FROM inventory_stock
                WHERE product_id = ?
                """,
                (rs, rowNum) -> new InventoryStockRecord(
                        rs.getString("product_id"),
                        rs.getString("product_name"),
                        rs.getInt("total_qty"),
                        rs.getInt("reserved_qty"),
                        rs.getInt("total_qty") - rs.getInt("reserved_qty"),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ),
                productId
        ).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown productId: " + productId));
    }

    private String firstAvailabilityError(List<CartItem> items) {
        for (CartItem item : items) {
            Integer available = jdbcTemplate.query(
                    "SELECT total_qty - reserved_qty FROM inventory_stock WHERE product_id = ?",
                    (rs, rowNum) -> rs.getInt(1),
                    item.productId()
            ).stream().findFirst().orElse(null);
            if (available == null) {
                return "No inventory configured for productId=" + item.productId();
            }
            if (available < item.quantity()) {
                return "Insufficient stock for productId=" + item.productId();
            }
        }
        return null;
    }

    private InventoryReservationRecord findReservationByOrderId(String orderId) {
        return jdbcTemplate.query(
                """
                SELECT reservation_id, order_id, cart_id, user_id, status, total_amount, reason, created_at, updated_at
                FROM inventory_reservations
                WHERE order_id = ?
                """,
                (rs, rowNum) -> new InventoryReservationRecord(
                        rs.getString("reservation_id"),
                        rs.getString("order_id"),
                        rs.getString("cart_id"),
                        rs.getString("user_id"),
                        rs.getString("status"),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("reason"),
                        fetchReservationItems(rs.getString("reservation_id")),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ),
                orderId
        ).stream().findFirst().orElse(null);
    }

    private List<CartItem> fetchReservationItems(String reservationId) {
        return jdbcTemplate.query(
                """
                SELECT product_id, product_name, quantity, unit_price
                FROM inventory_reservation_items
                WHERE reservation_id = ?
                ORDER BY id
                """,
                (rs, rowNum) -> new CartItem(
                        rs.getString("product_id"),
                        rs.getString("product_name"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("unit_price")
                ),
                reservationId
        );
    }

    private int attempt(Integer deliveryAttempt) {
        return deliveryAttempt == null ? 1 : deliveryAttempt;
    }

    private String resolveSagaId(Object event) {
        if (event instanceof OrderCreatedEvent orderCreatedEvent) {
            return orderCreatedEvent.orderId();
        }
        if (event instanceof PaymentFailedEvent paymentFailedEvent) {
            return paymentFailedEvent.orderId();
        }
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
            throw new IllegalStateException("Failed to publish inventory event", exception);
        }
    }
}
