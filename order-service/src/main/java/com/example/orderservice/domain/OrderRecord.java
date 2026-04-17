package com.example.orderservice.domain;

import com.example.contracts.CartItem;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record OrderRecord(
        String orderId,
        String cartId,
        String userId,
        String recipientName,
        String recipientEmail,
        String cardDetails,
        String paymentMethod,
        List<CartItem> items,
        BigDecimal totalAmount,
        String status,
        OffsetDateTime createdAt
) {
}
