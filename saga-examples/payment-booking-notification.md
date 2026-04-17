# Payment + Booking + Notification

## Services

- `booking-service`
- `payment-service`
- `notification-service`

## Goal

Reserve a booking, collect payment, confirm the booking, and notify the user.

## Happy path

1. Client calls `booking-service` to create a booking request.
2. `booking-service` saves the booking as `PENDING_PAYMENT`.
3. `booking-service` publishes `BookingCreated`.
4. `payment-service` consumes `BookingCreated` and starts payment collection.
5. `payment-service` saves payment as `SUCCESS`.
6. `payment-service` publishes `PaymentCompleted`.
7. `booking-service` consumes `PaymentCompleted` and marks booking `CONFIRMED`.
8. `booking-service` publishes `BookingConfirmed`.
9. `notification-service` consumes `BookingConfirmed` and sends confirmation email/SMS.

## Compensation path

1. `payment-service` cannot complete payment.
2. `payment-service` publishes `PaymentFailed`.
3. `booking-service` consumes `PaymentFailed`.
4. `booking-service` marks booking `CANCELLED`.
5. `booking-service` publishes `BookingCancelled`.
6. `notification-service` consumes `BookingCancelled` and informs the user.

## Service-owned state

- `booking-service`
  - `PENDING_PAYMENT`
  - `CONFIRMED`
  - `CANCELLED`
- `payment-service`
  - `INITIATED`
  - `SUCCESS`
  - `FAILED`
- `notification-service`
  - `PENDING`
  - `SENT`
  - `FAILED`

## Key events

### BookingCreated

```json
{
  "eventId": "7be7d7ee-a463-4d42-bf62-f358fe0ffc78",
  "eventType": "BookingCreated",
  "bookingId": "BK-1001",
  "userId": "USR-10",
  "recipientName": "John",
  "recipientEmail": "jbpvns@gmail.com",
  "amount": 499.99,
  "occurredAt": "2026-04-07T12:00:00Z"
}
```

### PaymentCompleted

```json
{
  "eventId": "dd553b7d-c614-4b1a-86d8-6da4202d70a1",
  "eventType": "PaymentCompleted",
  "bookingId": "BK-1001",
  "paymentId": "PAY-9001",
  "amount": 499.99,
  "occurredAt": "2026-04-07T12:01:00Z"
}
```

### PaymentFailed

```json
{
  "eventId": "707f447d-1f70-4d52-bff1-4af52661a6fa",
  "eventType": "PaymentFailed",
  "bookingId": "BK-1001",
  "reason": "INSUFFICIENT_FUNDS",
  "occurredAt": "2026-04-07T12:01:00Z"
}
```

## Choreography notes

- `booking-service` starts the saga.
- No central orchestrator decides the next step.
- Each service reacts only to domain events it cares about.
- Event publishing should happen after the local transaction commits.
- Consumers should be idempotent and store processed `eventId`.
