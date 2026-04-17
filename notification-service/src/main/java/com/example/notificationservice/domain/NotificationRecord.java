package com.example.notificationservice.domain;

import java.time.OffsetDateTime;

public record NotificationRecord(
        String notificationId,
        String paymentId,
        String orderId,
        String recipientName,
        String recipientEmail,
        String channel,
        String message,
        String status,
        OffsetDateTime createdAt
) {
}
