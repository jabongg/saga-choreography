package com.example.inventoryservice.domain;

public record UpsertInventoryRequest(
        String productName,
        Integer totalQuantity
) {
}
