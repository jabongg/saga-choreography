package com.example.contracts;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record InventoryFailedEvent(
        String orderId,
        String cartId,
        String userId,
        List<CartItem> items,
        BigDecimal totalAmount,
        String reason,
        OffsetDateTime occurredAt
) {
}
