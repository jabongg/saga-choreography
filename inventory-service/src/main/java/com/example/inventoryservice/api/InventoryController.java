package com.example.inventoryservice.api;

import com.example.inventoryservice.domain.InventoryReservationRecord;
import com.example.inventoryservice.domain.InventoryService;
import com.example.inventoryservice.domain.InventoryStockRecord;
import com.example.inventoryservice.domain.UpsertInventoryRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/stocks")
    public ResponseEntity<List<InventoryStockRecord>> stocks() {
        return ResponseEntity.ok(inventoryService.findAllStock());
    }

    @PutMapping("/stocks/{productId}")
    public ResponseEntity<InventoryStockRecord> upsert(@PathVariable String productId,
                                                       @RequestBody UpsertInventoryRequest request) {
        return ResponseEntity.ok(inventoryService.upsertStock(productId, request));
    }

    @GetMapping("/reservations")
    public ResponseEntity<List<InventoryReservationRecord>> reservations() {
        return ResponseEntity.ok(inventoryService.findReservations());
    }
}
