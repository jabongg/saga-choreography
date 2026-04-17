package com.example.cartservice.api;

import com.example.cartservice.domain.AddCartItemRequest;
import com.example.cartservice.domain.CartResponse;
import com.example.cartservice.domain.CartService;
import com.example.cartservice.domain.CheckoutCartRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/carts")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @PostMapping("/{userId}/items")
    public ResponseEntity<CartResponse> addItem(@PathVariable String userId, @RequestBody AddCartItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cartService.addItem(userId, request));
    }

    @PostMapping("/{userId}/checkout")
    public ResponseEntity<Map<String, String>> checkout(@PathVariable String userId, @RequestBody CheckoutCartRequest request) {
        String cartId = cartService.checkout(userId, request);
        return ResponseEntity.accepted().body(Map.of("cartId", cartId, "status", "CHECKOUT_EVENT_PUBLISHED"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }
}
