# Court Booking + Payment + Refund + Notification

## Services

- `court-booking-service`
- `payment-service`
- `refund-service`
- `notification-service`

## Goal

Book a sports court, collect payment, and handle compensation through refund if confirmation fails after the charge.

## Happy path

1. Client requests a court slot in `court-booking-service`.
2. `court-booking-service` saves reservation as `PENDING_PAYMENT`.
3. `court-booking-service` publishes `CourtBookingCreated`.
4. `payment-service` consumes the event and charges the customer.
5. `payment-service` publishes `PaymentCompleted`.
6. `court-booking-service` consumes `PaymentCompleted`.
7. `court-booking-service` reserves the slot and marks booking `CONFIRMED`.
8. `court-booking-service` publishes `CourtBookingConfirmed`.
9. `notification-service` sends booking success notification.

## Compensation path when booking confirmation fails after payment success

1. `payment-service` already published `PaymentCompleted`.
2. `court-booking-service` cannot confirm the slot because of a race or lock failure.
3. `court-booking-service` publishes `CourtBookingFailed`.
4. `refund-service` consumes `CourtBookingFailed`.
5. `refund-service` creates refund and publishes `RefundCompleted`.
6. `notification-service` consumes `RefundCompleted` and informs the user.

## Compensation path when refund fails

1. `refund-service` cannot issue the refund.
2. `refund-service` publishes `RefundFailed`.
3. `notification-service` alerts operations or the user.
4. `refund-service` retries or escalates manually.

## Service-owned state

- `court-booking-service`
  - `PENDING_PAYMENT`
  - `CONFIRMED`
  - `FAILED`
- `payment-service`
  - `SUCCESS`
  - `FAILED`
- `refund-service`
  - `INITIATED`
  - `COMPLETED`
  - `FAILED`

## Key events

### CourtBookingCreated

```json
{
  "eventId": "732fbcbb-d5bf-4f0d-9d5a-e60abef1976d",
  "eventType": "CourtBookingCreated",
  "bookingId": "CB-2001",
  "courtId": "COURT-11",
  "userId": "USR-88",
  "amount": 700.00,
  "occurredAt": "2026-04-07T12:20:00Z"
}
```

### CourtBookingFailed

```json
{
  "eventId": "e3d46d41-66f2-4be4-b210-1cd22274d2e0",
  "eventType": "CourtBookingFailed",
  "bookingId": "CB-2001",
  "paymentId": "PAY-9301",
  "amount": 700.00,
  "reason": "SLOT_ALREADY_BOOKED",
  "occurredAt": "2026-04-07T12:21:10Z"
}
```

### RefundCompleted

```json
{
  "eventId": "e576b14c-dbc2-463a-9ab2-e56d64d2aa02",
  "eventType": "RefundCompleted",
  "bookingId": "CB-2001",
  "paymentId": "PAY-9301",
  "refundId": "RF-1001",
  "amount": 700.00,
  "occurredAt": "2026-04-07T12:22:00Z"
}
```

## Choreography notes

- This example shows a real compensation step instead of only a user-facing side effect.
- `refund-service` should be idempotent on `paymentId`.
- `court-booking-service` should publish failure only after its own local booking confirmation fails.
