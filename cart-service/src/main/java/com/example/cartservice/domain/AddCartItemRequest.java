package com.example.cartservice.domain;

import java.math.BigDecimal;

public record AddCartItemRequest(String productId, String productName, int quantity, BigDecimal unitPrice) {
}
