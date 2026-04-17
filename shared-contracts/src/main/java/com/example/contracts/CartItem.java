package com.example.contracts;

import java.math.BigDecimal;

public record CartItem(
        String productId,
        String productName,
        int quantity,
        BigDecimal unitPrice
) {
}
