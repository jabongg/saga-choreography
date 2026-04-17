package com.example.contracts;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record NotificationFailedEvent(
        String paymentId,
        String orderId,
        String userId,
        BigDecimal paymentAmount,
        String reason,
        OffsetDateTime occurredAt
) {
}
