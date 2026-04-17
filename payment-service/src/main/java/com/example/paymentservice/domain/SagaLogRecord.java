package com.example.paymentservice.domain;

import java.time.OffsetDateTime;

public record SagaLogRecord(
        Long id,
        String sagaId,
        String stepName,
        String serviceName,
        String messageKey,
        String topicName,
        String payload,
        String status,
        Integer attempt,
        String errorMessage,
        OffsetDateTime createdAt
) {
}
