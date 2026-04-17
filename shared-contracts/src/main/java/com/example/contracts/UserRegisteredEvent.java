package com.example.contracts;

import java.time.OffsetDateTime;

public record UserRegisteredEvent(
        String userId,
        String fullName,
        String email,
        OffsetDateTime occurredAt
) {
}
