package com.example.cartservice.domain;

public record CheckoutCartRequest(
        String recipientName,
        String recipientEmail,
        String cardDetails,
        String paymentMethod
) {
}
