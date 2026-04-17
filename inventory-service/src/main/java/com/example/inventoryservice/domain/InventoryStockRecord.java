package com.example.inventoryservice.domain;

import java.time.OffsetDateTime;

public record InventoryStockRecord(
        String productId,
        String productName,
        Integer totalQuantity,
        Integer reservedQuantity,
        Integer availableQuantity,
        OffsetDateTime updatedAt
) {
}
