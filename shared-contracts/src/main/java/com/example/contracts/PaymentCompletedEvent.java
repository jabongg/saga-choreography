package com.example.contracts;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PaymentCompletedEvent(
        String paymentId,
        String orderId,
        String userId,
        String recipientName,
        String recipientEmail,
        String cardDetails,
        String paymentMethod,
        BigDecimal paymentAmount,
        OffsetDateTime occurredAt
) {
}
