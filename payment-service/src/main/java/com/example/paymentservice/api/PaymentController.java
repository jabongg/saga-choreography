package com.example.paymentservice.api;

import com.example.paymentservice.domain.PaymentRecord;
import com.example.paymentservice.domain.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    public ResponseEntity<List<PaymentRecord>> all() {
        return ResponseEntity.ok(paymentService.findAll());
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentRecord> get(@PathVariable String paymentId) {
        return ResponseEntity.ok(paymentService.get(paymentId));
    }
}
