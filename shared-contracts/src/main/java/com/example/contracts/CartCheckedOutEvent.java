package com.example.contracts;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record CartCheckedOutEvent(
        String cartId,
        String userId,
        String recipientName,
        String recipientEmail,
        String cardDetails,
        String paymentMethod,
        List<CartItem> items,
        BigDecimal totalAmount,
        OffsetDateTime occurredAt
) {
}
