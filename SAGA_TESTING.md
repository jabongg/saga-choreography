# Saga Testing Guide

The saga is now inventory-first:

`cart.checked-out -> order.created -> inventory.reserved -> payment.completed -> notification`

Compensation paths:

- `inventory.failed -> order.cancelled`
- `payment.failed -> inventory.released -> order.cancelled`

## Services and ports

- `order-service`: `8082`
- `inventory-service`: `8086`
- `payment-service`: `8083`
- `notification-service`: `8084`

All four services expose:

- `GET /api/saga/logs`
- `POST /api/saga/logs/{logId}/replay`
- `POST /api/saga/failures`
- `DELETE /api/saga/failures`

## Seed inventory first

Every realistic saga test now starts by loading stock:

```bash
curl -X PUT http://localhost:8086/api/inventory/stocks/SKU-100 \
  -H 'Content-Type: application/json' \
  -d '{
    "productName": "Running Shoes",
    "totalQuantity": 10
  }'
```

Inspect state:

```bash
curl http://localhost:8086/api/inventory/stocks
curl http://localhost:8086/api/inventory/reservations
```

## Failure hooks

Order service:

- `order.create.before-persist`
- `order.inventory-reserved.before-update`
- `order.complete.before-update`
- `order.compensate.before-update`
- `order.compensate.after-update`

Inventory service:

- `inventory.reserve.before-check`
- `inventory.reserve.after-persist`
- `inventory.release.before-update`
- `inventory.confirm.before-update`

Payment service:

- `payment.charge.before-persist`
- `payment.charge.after-persist`

Notification service:

- `notification.send.before-email`
- `notification.send.after-email`
- `notification.send.after-persist`

Configure a failure:

```bash
curl -X POST http://localhost:8086/api/saga/failures \
  -H 'Content-Type: application/json' \
  -d '{
    "stepName": "inventory.reserve.before-check",
    "remainingFailures": 4,
    "message": "inject failure"
  }'
```

Clear failures:

```bash
curl -X DELETE http://localhost:8082/api/saga/failures
curl -X DELETE http://localhost:8086/api/saga/failures
curl -X DELETE http://localhost:8083/api/saga/failures
curl -X DELETE http://localhost:8084/api/saga/failures
```

## Scenario 1: Inventory Failure

Seed less stock than the cart needs, or inject failure on `inventory.reserve.before-check`.

Expected result:

- `order-service` creates the order.
- `inventory-service` emits `inventory.failed`.
- `order-service` marks the order `CANCELLED`.
- `payment-service` does nothing.

Verify:

```bash
curl http://localhost:8082/api/orders
curl http://localhost:8083/api/payments
curl http://localhost:8086/api/saga/logs
```

## Scenario 2: Payment Failure After Inventory Reserved

Keep inventory available and fail payment:

```bash
curl -X POST http://localhost:8083/api/saga/failures \
  -H 'Content-Type: application/json' \
  -d '{
    "stepName": "payment.charge.before-persist",
    "remainingFailures": 4,
    "message": "payment failure after reservation"
  }'
```

Expected result:

- `inventory-service` reserves stock.
- `payment-service` retries, then emits `payment.failed`.
- `inventory-service` releases the reservation and emits `inventory.released`.
- `order-service` cancels the order.

Verify:

```bash
curl http://localhost:8082/api/orders
curl http://localhost:8086/api/inventory/stocks
curl http://localhost:8086/api/inventory/reservations
curl http://localhost:8083/api/payments
```

## Scenario 3: Notification Failure Is Non-Critical

Fail notification delivery:

```bash
curl -X POST http://localhost:8084/api/saga/failures \
  -H 'Content-Type: application/json' \
  -d '{
    "stepName": "notification.send.before-email",
    "remainingFailures": 4,
    "message": "notification failure"
  }'
```

Expected result:

- inventory stays confirmed
- payment stays successful
- order stays `COMPLETED`
- notification retries and eventually lands in DLT

Replay after clearing the failure:

```bash
curl -X DELETE http://localhost:8084/api/saga/failures
curl http://localhost:8084/api/saga/logs
curl -X POST http://localhost:8084/api/saga/logs/<LOG_ID>/replay
```

## Scenario 4: Duplicate Event / Idempotency

Replay a prior event through Kafka:

```bash
curl http://localhost:8086/api/saga/logs
curl -X POST http://localhost:8086/api/saga/logs/<LOG_ID>/replay
```

Useful replay points:

- `order.created` into `inventory-service`
- `inventory.reserved` into `payment-service`
- `payment.completed` into `notification-service`

Expected result:

- no duplicate reservation for the same `order_id`
- no duplicate payment for the same `order_id`
- no duplicate notification for the same `payment_id`
- saga logs show `SKIPPED` entries for duplicate handling

## Scenario 5: Eventual Consistency / Service Down

To test lag after reservation or payment:

1. Start the stack.
2. Stop `order-service` or `notification-service`.
3. Run checkout.
4. Start the stopped service again.

Expected result:

- Kafka re-delivers on restart.
- final state converges without manual DB edits.
- `saga_log.created_at` timestamps show the delay window.

## Scenario 6: Partial DB Failure

Use an `after-persist` failure point:

```bash
curl -X POST http://localhost:8086/api/saga/failures \
  -H 'Content-Type: application/json' \
  -d '{
    "stepName": "inventory.reserve.after-persist",
    "remainingFailures": 1,
    "message": "partial db failure"
  }'
```

or

```bash
curl -X POST http://localhost:8084/api/saga/failures \
  -H 'Content-Type: application/json' \
  -d '{
    "stepName": "notification.send.after-persist",
    "remainingFailures": 1,
    "message": "partial db failure"
  }'
```

Expected result:

- the local transaction rolls back
- retry picks the event back up
- the final state remains single-write and consistent

## Replay Notes

- Retry topics and DLT topics are auto-created by Spring Kafka.
- Replay republishes the original payload back to Kafka, not directly into service methods.
- `saga_log` is the fastest place to debug ordering, compensation, and timing.
