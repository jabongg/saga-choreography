package com.example.cartservice.domain;

import com.example.contracts.CartCheckedOutEvent;
import com.example.contracts.CartItem;
import com.example.contracts.OrderCancelledEvent;
import com.example.contracts.TopicNames;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class CartService {

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, CartCheckedOutEvent> kafkaTemplate;

    public CartService(JdbcTemplate jdbcTemplate,
                       KafkaTemplate<String, CartCheckedOutEvent> kafkaTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
    }

    public CartResponse addItem(String userId, AddCartItemRequest request) {
        validateUserId(userId);
        validateItem(request);

        String cartId = findOrCreateOpenCart(userId);
        jdbcTemplate.update(
                """
                INSERT INTO cart_items (cart_id, product_id, product_name, quantity, unit_price)
                VALUES (?, ?, ?, ?, ?)
                """,
                cartId,
                request.productId().trim(),
                request.productName().trim(),
                request.quantity(),
                request.unitPrice()
        );
        return toResponse(cartId, userId, fetchItems(cartId));
    }

    @Transactional
    public String checkout(String userId, CheckoutCartRequest request) {
        validateUserId(userId);
        validateCheckout(request);

        String cartId = findOpenCart(userId);
        if (cartId == null) {
            throw new IllegalArgumentException("Cart is empty for userId: " + userId);
        }
        List<CartItem> items = fetchItems(cartId);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty for userId: " + userId);
        }

        BigDecimal totalAmount = items.stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        jdbcTemplate.update(
                "UPDATE carts SET status = ?, checked_out_at = ? WHERE cart_id = ?",
                "CHECKED_OUT",
                OffsetDateTime.now(),
                cartId
        );

        sendCheckedOutEvent(
                TopicNames.CART_CHECKED_OUT,
                cartId,
                new CartCheckedOutEvent(
                        cartId,
                        userId,
                        request.recipientName().trim(),
                        request.recipientEmail().trim(),
                        request.cardDetails().trim(),
                        request.paymentMethod().trim(),
                        List.copyOf(items),
                        totalAmount,
                        OffsetDateTime.now()
                )
        );

        return cartId;
    }

    @Transactional
    @KafkaListener(
            topics = TopicNames.ORDER_CANCELLED,
            groupId = "cart-service",
            containerFactory = "orderCancelledKafkaListenerContainerFactory"
    )
    public void onOrderCancelled(OrderCancelledEvent event) {
        int updated = jdbcTemplate.update(
                "UPDATE carts SET status = ?, checked_out_at = NULL WHERE cart_id = ? AND status = ?",
                "OPEN",
                event.cartId(),
                "CHECKED_OUT"
        );
        if (updated == 0) {
            return;
        }
    }

    private CartResponse toResponse(String cartId, String userId, List<CartItem> items) {
        BigDecimal totalAmount = items.stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(cartId, userId, List.copyOf(items), totalAmount);
    }

    private String findOrCreateOpenCart(String userId) {
        String existingCartId = findOpenCart(userId);
        if (existingCartId != null) {
            return existingCartId;
        }

        String cartId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO carts (cart_id, user_id, status, created_at) VALUES (?, ?, ?, ?)",
                cartId,
                userId,
                "OPEN",
                OffsetDateTime.now()
        );
        return cartId;
    }

    private String findOpenCart(String userId) {
        return jdbcTemplate.query(
                "SELECT cart_id FROM carts WHERE user_id = ? AND status = ? ORDER BY created_at DESC LIMIT 1",
                (rs, rowNum) -> rs.getString("cart_id"),
                userId,
                "OPEN"
        ).stream().findFirst().orElse(null);
    }

    private List<CartItem> fetchItems(String cartId) {
        return jdbcTemplate.query(
                """
                SELECT product_id, product_name, quantity, unit_price
                FROM cart_items
                WHERE cart_id = ?
                ORDER BY id
                """,
                (rs, rowNum) -> new CartItem(
                        rs.getString("product_id"),
                        rs.getString("product_name"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("unit_price")
                ),
                cartId
        );
    }

    private void validateUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("userId is required");
        }
    }

    private void validateItem(AddCartItemRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (isBlank(request.productId())) {
            throw new IllegalArgumentException("productId is required");
        }
        if (isBlank(request.productName())) {
            throw new IllegalArgumentException("productName is required");
        }
        if (request.quantity() <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }
        if (request.unitPrice() == null || request.unitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("unitPrice must be greater than 0");
        }
    }

    private void validateCheckout(CheckoutCartRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (isBlank(request.recipientName())) {
            throw new IllegalArgumentException("recipientName is required");
        }
        if (isBlank(request.recipientEmail())) {
            throw new IllegalArgumentException("recipientEmail is required");
        }
        if (isBlank(request.cardDetails())) {
            throw new IllegalArgumentException("cardDetails is required");
        }
        if (isBlank(request.paymentMethod())) {
            throw new IllegalArgumentException("paymentMethod is required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void sendCheckedOutEvent(String topic, String key, CartCheckedOutEvent event) {
        try {
            kafkaTemplate.send(topic, key, event).get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to publish cart checkout event", exception);
        }
    }
}
