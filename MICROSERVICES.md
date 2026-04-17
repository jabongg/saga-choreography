# Microservice Layout

This workspace is now organized around a Kafka-based eCommerce flow:

- `shared-contracts`
- `user-service`
- `cart-service`
- `order-service`
- `inventory-service`
- `payment-service`
- `notification-service`

## Event flow

1. `user-service` registers users and can publish `UserRegisteredEvent`
2. `cart-service` accepts cart item adds and checkout
3. Checkout publishes `CartCheckedOutEvent`
4. `order-service` consumes it and publishes `OrderCreatedEvent`
5. `inventory-service` reserves stock and publishes `InventoryReservedEvent` or `InventoryFailedEvent`
6. `payment-service` consumes `InventoryReservedEvent` and publishes `PaymentCompletedEvent`
7. `inventory-service` confirms stock on payment success or releases it on payment failure
8. `notification-service` consumes `PaymentCompletedEvent` and sends the email notification

## Kafka topics

- `user.registered`
- `cart.checked-out`
- `order.created`
- `inventory.reserved`
- `inventory.failed`
- `inventory.released`
- `payment.completed`

## Local ports

- `user-service`: `8080`
- `cart-service`: `8081`
- `order-service`: `8082`
- `inventory-service`: `8086`
- `payment-service`: `8083`
- `notification-service`: `8084`

## Example flow

1. Create a user in `user-service`
2. Add items to the user's cart in `cart-service`
3. Checkout the cart in `cart-service`
4. Let Kafka drive order creation, payment, and notification

## Example checkout request

```json
{
  "recipientName": "John",
  "recipientEmail": "jbpvns@gmail.com",
  "cardDetails": "****1234",
  "paymentMethod": "CARD"
}
```
