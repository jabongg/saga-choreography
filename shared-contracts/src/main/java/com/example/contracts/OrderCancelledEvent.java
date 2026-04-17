package com.example.contracts;

import java.time.OffsetDateTime;

public record OrderCancelledEvent(
        String orderId,
        String cartId,
        String userId,
        String reason,
        OffsetDateTime occurredAt
) {
}
