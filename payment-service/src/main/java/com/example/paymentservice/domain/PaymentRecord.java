package com.example.paymentservice.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PaymentRecord(
        String paymentId,
        String orderId,
        String userId,
        String recipientName,
        String recipientEmail,
        String cardDetails,
        String paymentMethod,
        BigDecimal paymentAmount,
        String status,
        OffsetDateTime createdAt
) {
}
