package com.example.cartservice.domain;

import com.example.contracts.CartItem;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(String cartId, String userId, List<CartItem> items, BigDecimal totalAmount) {
}
