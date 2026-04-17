package com.example.contracts;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PaymentFailedEvent(
        String orderId,
        String cartId,
        String userId,
        BigDecimal paymentAmount,
        String reason,
        OffsetDateTime occurredAt
) {
}
