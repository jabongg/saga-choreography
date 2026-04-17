# Order + Payment + Inventory + Notification

## Services

- `order-service`
- `inventory-service`
- `payment-service`
- `notification-service`

## Goal

Accept an order, reserve stock, take payment, confirm the order, and notify the customer.

## Happy path

1. Client creates an order in `order-service`.
2. `order-service` saves order as `PENDING_INVENTORY`.
3. `order-service` publishes `OrderCreated`.
4. `inventory-service` consumes `OrderCreated` and reserves stock.
5. `inventory-service` publishes `InventoryReserved`.
6. `payment-service` consumes `InventoryReserved` and charges payment.
7. `payment-service` publishes `PaymentCompleted`.
8. `order-service` consumes `PaymentCompleted` and marks order `CONFIRMED`.
9. `order-service` publishes `OrderConfirmed`.
10. `notification-service` consumes `OrderConfirmed` and sends confirmation.

## Compensation path when inventory fails

1. `inventory-service` cannot reserve stock.
2. `inventory-service` publishes `InventoryReservationFailed`.
3. `order-service` consumes the event and marks order `REJECTED`.
4. `order-service` publishes `OrderRejected`.
5. `notification-service` informs the customer.

## Compensation path when payment fails after inventory reservation

1. `payment-service` fails to charge the user.
2. `payment-service` publishes `PaymentFailed`.
3. `inventory-service` consumes `PaymentFailed` and releases stock.
4. `inventory-service` publishes `InventoryReleased`.
5. `order-service` consumes `PaymentFailed` or `InventoryReleased` and marks order `CANCELLED`.
6. `notification-service` sends failure/cancellation message.

## Service-owned state

- `order-service`
  - `PENDING_INVENTORY`
  - `PENDING_PAYMENT`
  - `CONFIRMED`
  - `REJECTED`
  - `CANCELLED`
- `inventory-service`
  - `RESERVED`
  - `RELEASED`
  - `FAILED`
- `payment-service`
  - `INITIATED`
  - `SUCCESS`
  - `FAILED`

## Key events

### OrderCreated

```json
{
  "eventId": "84773664-4b7f-4ba6-8fd9-fcf390c0f4f1",
  "eventType": "OrderCreated",
  "orderId": "ORD-5001",
  "userId": "USR-44",
  "items": [
    {
      "sku": "BAT-01",
      "quantity": 1
    }
  ],
  "totalAmount": 1299.00,
  "occurredAt": "2026-04-07T12:10:00Z"
}
```

### InventoryReserved

```json
{
  "eventId": "67bf2f89-c124-4815-bf28-f248e0a09cf4",
  "eventType": "InventoryReserved",
  "orderId": "ORD-5001",
  "reservationId": "INV-7001",
  "totalAmount": 1299.00,
  "occurredAt": "2026-04-07T12:10:10Z"
}
```

### InventoryReservationFailed

```json
{
  "eventId": "dc1cefd1-cf38-4985-b1b8-0640fb09abdc",
  "eventType": "InventoryReservationFailed",
  "orderId": "ORD-5001",
  "reason": "OUT_OF_STOCK",
  "occurredAt": "2026-04-07T12:10:05Z"
}
```

## Choreography notes

- This is a stronger Saga example than notification alone because multiple critical business participants own compensations.
- `inventory-service` and `payment-service` both need idempotent consumers.
- Duplicate events must not double-reserve or double-charge.
