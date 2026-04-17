package com.example.inventoryservice.domain;

import com.example.contracts.CartItem;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record InventoryReservationRecord(
        String reservationId,
        String orderId,
        String cartId,
        String userId,
        String status,
        BigDecimal totalAmount,
        String reason,
        List<CartItem> items,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
