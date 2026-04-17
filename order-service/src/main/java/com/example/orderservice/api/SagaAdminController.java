package com.example.orderservice.api;

import com.example.orderservice.domain.OrderService;
import com.example.orderservice.domain.SagaControlService;
import com.example.orderservice.domain.SagaLogRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/saga")
public class SagaAdminController {

    private final OrderService orderService;
    private final SagaControlService sagaControlService;

    public SagaAdminController(OrderService orderService, SagaControlService sagaControlService) {
        this.orderService = orderService;
        this.sagaControlService = sagaControlService;
    }

    @GetMapping("/logs")
    public ResponseEntity<List<SagaLogRecord>> logs() {
        return ResponseEntity.ok(orderService.findSagaLogs());
    }

    @PostMapping("/logs/{logId}/replay")
    public ResponseEntity<Void> replay(@PathVariable Long logId) {
        orderService.replaySagaLog(logId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/failures")
    public ResponseEntity<Map<String, String>> configureFailure(@RequestBody FailureRequest request) {
        sagaControlService.configureFailure(request.stepName(), request.remainingFailures(), request.message());
        return ResponseEntity.accepted().body(Map.of("status", "configured"));
    }

    @DeleteMapping("/failures")
    public ResponseEntity<Void> clearFailures() {
        sagaControlService.clearFailures();
        return ResponseEntity.noContent().build();
    }

    public record FailureRequest(
            String stepName,
            int remainingFailures,
            String message
    ) {
    }
}
