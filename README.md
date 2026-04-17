# ECommerce Microservices Workspace

This workspace is a Kafka-based Spring Boot microservice setup for an eCommerce flow.

## Modules

- `shared-contracts`
- `user-service`
- `cart-service`
- `order-service`
- `inventory-service`
- `payment-service`
- `notification-service`

## Prerequisites

- Java 21
- Maven wrapper via `./mvnw`
- Kafka running on `localhost:9092`
- PostgreSQL running on `localhost:5432`

## Build

From the workspace root:

```bash
./mvnw -q -DskipTests compile
```

## Docker stack

Build and run everything with Docker Compose:

```bash
docker compose up --build
```

This starts:

- PostgreSQL
- Kafka
- `user-service`
- `cart-service`
- `order-service`
- `inventory-service`
- `payment-service`
- `notification-service`

Each service gets its own PostgreSQL database inside the same Postgres container:

- `user_db`
- `cart_db`
- `order_db`
- `inventory_db`
- `payment_db`
- `notification_db`

## Run services

Open a separate terminal for each service and run these from the workspace root.

### user-service

```bash
./mvnw -pl user-service spring-boot:run
```

Runs on `http://localhost:8080`

### cart-service

```bash
./mvnw -pl cart-service spring-boot:run
```

Runs on `http://localhost:8081`

### order-service

```bash
./mvnw -pl order-service spring-boot:run
```

Runs on `http://localhost:8082`

### payment-service

```bash
./mvnw -pl payment-service spring-boot:run
```

Runs on `http://localhost:8083`

### inventory-service

```bash
./mvnw -pl inventory-service spring-boot:run
```

Runs on `http://localhost:8086`

### notification-service

```bash
./mvnw -pl notification-service spring-boot:run
```

Runs on `http://localhost:8084`

## Basic flow

### 1. Create a user

```bash
curl -X POST http://localhost:8080/api/users \
  -H 'Content-Type: application/json' \
  -d '{
    "fullName": "John Doe",
    "email": "jbpvns@gmail.com"
  }'
```

### 2. Add an item to the cart

Replace `<USER_ID>` with the `userId` returned by `user-service`.

```bash
curl -X POST http://localhost:8081/api/carts/<USER_ID>/items \
  -H 'Content-Type: application/json' \
  -d '{
    "productId": "SKU-100",
    "productName": "Running Shoes",
    "quantity": 1,
    "unitPrice": 2499.00
  }'
```

### 3. Checkout the cart

```bash
curl -X POST http://localhost:8081/api/carts/<USER_ID>/checkout \
  -H 'Content-Type: application/json' \
  -d '{
    "recipientName": "John Doe",
    "recipientEmail": "jbpvns@gmail.com",
    "cardDetails": "****1234",
    "paymentMethod": "CARD"
  }'
```

Before checkout succeeds, seed inventory:

```bash
curl -X PUT http://localhost:8086/api/inventory/stocks/SKU-100 \
  -H 'Content-Type: application/json' \
  -d '{
    "productName": "Running Shoes",
    "totalQuantity": 10
  }'
```

Checkout now publishes Kafka events which drive:

- order creation
- inventory reservation
- payment completion
- order completion
- notification sending

## Read model endpoints

### Orders

```bash
curl http://localhost:8082/api/orders
```

### Inventory

```bash
curl http://localhost:8086/api/inventory/stocks
curl http://localhost:8086/api/inventory/reservations
```

### Payments

```bash
curl http://localhost:8083/api/payments
```

### Notifications

```bash
curl http://localhost:8084/api/notifications
```

## Notes

- Services now use PostgreSQL-backed persistence through Spring JDBC.
- `inventory-service` is now the first saga gate and manages reserve, confirm, and release.
- `notification-service` is wired for SMTP, but mail credentials still need to be configured.
- Saga failure injection, retry/DLT flow, replay, and validation steps are documented in [SAGA_TESTING.md](/Users/apple/Documents/dev/projects/ollama/SAGA_TESTING.md).
- Kafka topics are defined in [TopicNames.java](/Users/apple/Documents/dev/projects/ollama/shared-contracts/src/main/java/com/example/contracts/TopicNames.java).
- Docker Compose setup is in [docker-compose.yml](/Users/apple/Documents/dev/projects/ollama/docker-compose.yml).
