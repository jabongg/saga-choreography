package com.example.orderservice.domain;

import com.example.contracts.CartCheckedOutEvent;
import com.example.contracts.CartItem;
import com.example.contracts.InventoryFailedEvent;
import com.example.contracts.InventoryReleasedEvent;
import com.example.contracts.InventoryReservedEvent;
import com.example.contracts.OrderCancelledEvent;
import com.example.contracts.OrderCreatedEvent;
import com.example.contracts.PaymentCompletedEvent;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class OrderService {

    private static final String SERVICE_NAME = "order-service";

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final SagaControlService sagaControlService;

    public OrderService(JdbcTemplate jdbcTemplate,
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
            topics = TopicNames.CART_CHECKED_OUT,
            groupId = "order-service",
            containerFactory = "cartCheckedOutKafkaListenerContainerFactory"
    )
    public void onCartCheckedOut(CartCheckedOutEvent event,
                                 @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                                 @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {
        if (findByCartId(event.cartId()) != null) {
            logSaga(event.cartId(), "order.create", key, TopicNames.CART_CHECKED_OUT, event, "SKIPPED", attempt(deliveryAttempt), "Duplicate cart checkout ignored");
            return;
        }
        logSaga(event.cartId(), "order.create", key, TopicNames.CART_CHECKED_OUT, event, "STARTED", attempt(deliveryAttempt), null);
        sagaControlService.failIfConfigured("order.create.before-persist");

        String orderId = UUID.randomUUID().toString();
        OffsetDateTime createdAt = OffsetDateTime.now();

        OrderRecord order = new OrderRecord(
                orderId,
                event.cartId(),
                event.userId(),
                event.recipientName(),
                event.recipientEmail(),
                event.cardDetails(),
                event.paymentMethod(),
                event.items(),
                event.totalAmount(),
                "CREATED",
                createdAt
        );
        jdbcTemplate.update(
                """
                INSERT INTO orders
                (order_id, cart_id, user_id, recipient_name, recipient_email, card_details, payment_method, total_amount, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                order.orderId(),
                order.cartId(),
                order.userId(),
                order.recipientName(),
                order.recipientEmail(),
                order.cardDetails(),
                order.paymentMethod(),
                order.totalAmount(),
                order.status(),
                order.createdAt()
        );
        for (CartItem item : event.items()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO order_items (order_id, product_id, product_name, quantity, unit_price)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    orderId,
                    item.productId(),
                    item.productName(),
                    item.quantity(),
                    item.unitPrice()
            );
        }

        sendEvent(
                TopicNames.ORDER_CREATED,
                orderId,
                new OrderCreatedEvent(
                        orderId,
                        event.cartId(),
                        event.userId(),
                        event.recipientName(),
                        event.recipientEmail(),
                        event.cardDetails(),
                        event.paymentMethod(),
                        event.items(),
                        event.totalAmount(),
                        createdAt
                )
        );
        logSaga(orderId, "order.create", orderId, TopicNames.ORDER_CREATED, order, "COMPLETED", attempt(deliveryAttempt), null);
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
            groupId = "order-service",
            containerFactory = "inventoryReservedKafkaListenerContainerFactory"
    )
    public void onInventoryReserved(InventoryReservedEvent event,
                                    @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                                    @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {
        OrderRecord order = mapOrder(event.orderId());
        if (order == null) {
            throw new IllegalArgumentException("Unknown orderId for inventory reservation: " + event.orderId());
        }
        if ("INVENTORY_RESERVED".equals(order.status()) || "COMPLETED".equals(order.status())) {
            logSaga(event.orderId(), "order.inventory-reserved", key, TopicNames.INVENTORY_RESERVED, event, "SKIPPED", attempt(deliveryAttempt), "Duplicate inventory reservation ignored");
            return;
        }
        if ("CANCELLED".equals(order.status())) {
            logSaga(event.orderId(), "order.inventory-reserved", key, TopicNames.INVENTORY_RESERVED, event, "SKIPPED", attempt(deliveryAttempt), "Cancelled order ignored");
            return;
        }

        logSaga(event.orderId(), "order.inventory-reserved", key, TopicNames.INVENTORY_RESERVED, event, "STARTED", attempt(deliveryAttempt), null);
        sagaControlService.failIfConfigured("order.inventory-reserved.before-update");
        jdbcTemplate.update(
                "UPDATE orders SET status = ? WHERE order_id = ?",
                "INVENTORY_RESERVED",
                event.orderId()
        );
        logSaga(event.orderId(), "order.inventory-reserved", key, TopicNames.INVENTORY_RESERVED, event, "COMPLETED", attempt(deliveryAttempt), null);
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
            topics = TopicNames.PAYMENT_COMPLETED,
            groupId = "order-service",
            containerFactory = "paymentCompletedKafkaListenerContainerFactory"
    )
    public void onPaymentCompleted(PaymentCompletedEvent event,
                                   @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                                   @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {
        OrderRecord order = mapOrder(event.orderId());
        if (order == null) {
            throw new IllegalArgumentException("Unknown orderId for payment completion: " + event.orderId());
        }
        if ("COMPLETED".equals(order.status())) {
            logSaga(event.orderId(), "order.complete", key, TopicNames.PAYMENT_COMPLETED, event, "SKIPPED", attempt(deliveryAttempt), "Duplicate payment completion ignored");
            return;
        }
        if ("CANCELLED".equals(order.status())) {
            logSaga(event.orderId(), "order.complete", key, TopicNames.PAYMENT_COMPLETED, event, "SKIPPED", attempt(deliveryAttempt), "Cancelled order ignored");
            return;
        }

        logSaga(event.orderId(), "order.complete", key, TopicNames.PAYMENT_COMPLETED, event, "STARTED", attempt(deliveryAttempt), null);
        sagaControlService.failIfConfigured("order.complete.before-update");
        jdbcTemplate.update(
                "UPDATE orders SET status = ? WHERE order_id = ?",
                "COMPLETED",
                event.orderId()
        );
        logSaga(event.orderId(), "order.complete", key, TopicNames.PAYMENT_COMPLETED, event, "COMPLETED", attempt(deliveryAttempt), null);
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
            topics = TopicNames.INVENTORY_FAILED,
            groupId = "order-service",
            containerFactory = "inventoryFailedKafkaListenerContainerFactory"
    )
    public void onInventoryFailed(InventoryFailedEvent event,
                                  @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                                  @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {
        cancelOrder(event.orderId(), event.userId(), event.reason(), key, TopicNames.INVENTORY_FAILED, event, deliveryAttempt);
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
            topics = TopicNames.INVENTORY_RELEASED,
            groupId = "order-service",
            containerFactory = "inventoryReleasedKafkaListenerContainerFactory"
    )
    public void onInventoryReleased(InventoryReleasedEvent event,
                                    @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                                    @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {
        cancelOrder(event.orderId(), event.userId(), event.reason(), key, TopicNames.INVENTORY_RELEASED, event, deliveryAttempt);
    }

    @DltHandler
    public void onDlt(Object event,
                      @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                      @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic) {
        logSaga(resolveSagaId(event), "order.dlt", key, topic, event, "DLT", 0, "Message moved to dead-letter topic");
    }

    public List<OrderRecord> findAll() {
        return jdbcTemplate.query(
                """
                SELECT order_id, cart_id, user_id, recipient_name, recipient_email, card_details, payment_method, total_amount, status, created_at
                FROM orders
                ORDER BY created_at DESC
                """,
                (rs, rowNum) -> mapOrder(rs.getString("order_id"))
        );
    }

    public OrderRecord get(String orderId) {
        OrderRecord record = mapOrder(orderId);
        if (record == null) {
            throw new IllegalArgumentException("Unknown orderId: " + orderId);
        }
        return record;
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
            case TopicNames.CART_CHECKED_OUT -> readPayload(logRecord.payload(), CartCheckedOutEvent.class);
            case TopicNames.INVENTORY_RESERVED -> readPayload(logRecord.payload(), InventoryReservedEvent.class);
            case TopicNames.INVENTORY_FAILED -> readPayload(logRecord.payload(), InventoryFailedEvent.class);
            case TopicNames.INVENTORY_RELEASED -> readPayload(logRecord.payload(), InventoryReleasedEvent.class);
            case TopicNames.PAYMENT_COMPLETED -> readPayload(logRecord.payload(), PaymentCompletedEvent.class);
            default -> throw new IllegalArgumentException("Replay is not supported for topic: " + logRecord.topicName());
        };
        sendEvent(logRecord.topicName(), logRecord.messageKey(), replayEvent);
        logSaga(logRecord.sagaId(), "order.replay", logRecord.messageKey(), logRecord.topicName(), replayEvent, "REPLAYED", logRecord.attempt(), null);
    }

    private void cancelOrder(String orderId,
                             String userId,
                             String reason,
                             String key,
                             String topic,
                             Object payload,
                             Integer deliveryAttempt) {
        OrderRecord order = mapOrder(orderId);
        if (order == null) {
            throw new IllegalArgumentException("Unknown orderId: " + orderId);
        }
        if ("CANCELLED".equals(order.status())) {
            logSaga(orderId, "order.cancel", key, topic, payload, "SKIPPED", attempt(deliveryAttempt), "Duplicate compensation ignored");
            return;
        }

        logSaga(orderId, "order.cancel", key, topic, payload, "STARTED", attempt(deliveryAttempt), null);
        sagaControlService.failIfConfigured("order.compensate.before-update");
        jdbcTemplate.update(
                "UPDATE orders SET status = ? WHERE order_id = ?",
                "CANCELLED",
                orderId
        );
        sagaControlService.failIfConfigured("order.compensate.after-update");
        sendEvent(
                TopicNames.ORDER_CANCELLED,
                orderId,
                new OrderCancelledEvent(
                        order.orderId(),
                        order.cartId(),
                        userId,
                        reason,
                        OffsetDateTime.now()
                )
        );
        logSaga(orderId, "order.cancel", key, topic, payload, "COMPLETED", attempt(deliveryAttempt), null);
    }

    private OrderRecord mapOrder(String orderId) {
        return jdbcTemplate.query(
                """
                SELECT order_id, cart_id, user_id, recipient_name, recipient_email, card_details, payment_method, total_amount, status, created_at
                FROM orders
                WHERE order_id = ?
                """,
                (rs, rowNum) -> new OrderRecord(
                        rs.getString("order_id"),
                        rs.getString("cart_id"),
                        rs.getString("user_id"),
                        rs.getString("recipient_name"),
                        rs.getString("recipient_email"),
                        rs.getString("card_details"),
                        rs.getString("payment_method"),
                        fetchItems(rs.getString("order_id")),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                orderId
        ).stream().findFirst().orElse(null);
    }

    private OrderRecord findByCartId(String cartId) {
        return jdbcTemplate.query(
                """
                SELECT order_id, cart_id, user_id, recipient_name, recipient_email, card_details, payment_method, total_amount, status, created_at
                FROM orders
                WHERE cart_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new OrderRecord(
                        rs.getString("order_id"),
                        rs.getString("cart_id"),
                        rs.getString("user_id"),
                        rs.getString("recipient_name"),
                        rs.getString("recipient_email"),
                        rs.getString("card_details"),
                        rs.getString("payment_method"),
                        fetchItems(rs.getString("order_id")),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                cartId
        ).stream().findFirst().orElse(null);
    }

    private List<CartItem> fetchItems(String orderId) {
        return jdbcTemplate.query(
                """
                SELECT product_id, product_name, quantity, unit_price
                FROM order_items
                WHERE order_id = ?
                ORDER BY id
                """,
                (rs, rowNum) -> new CartItem(
                        rs.getString("product_id"),
                        rs.getString("product_name"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("unit_price")
                ),
                orderId
        );
    }

    private int attempt(Integer deliveryAttempt) {
        return deliveryAttempt == null ? 1 : deliveryAttempt;
    }

    private String resolveSagaId(Object event) {
        if (event instanceof CartCheckedOutEvent cartCheckedOutEvent) {
            return cartCheckedOutEvent.cartId();
        }
        if (event instanceof InventoryReservedEvent inventoryReservedEvent) {
            return inventoryReservedEvent.orderId();
        }
        if (event instanceof InventoryFailedEvent inventoryFailedEvent) {
            return inventoryFailedEvent.orderId();
        }
        if (event instanceof InventoryReleasedEvent inventoryReleasedEvent) {
            return inventoryReleasedEvent.orderId();
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
            throw new IllegalStateException("Failed to publish order event", exception);
        }
    }
}
