# Data Flow Architecture

## Overview

This document describes how data flows through the High-Throughput Booking and Payment Orchestration System, including data ownership, propagation patterns, consistency models, and event-driven workflows.

---

## 🗂 Data Ownership Model

### Database-per-Service Pattern

Each microservice owns its data exclusively. No other service can directly access its database.

```
┌─────────────────────────────────────────────────────────┐
│                  Service Boundaries                     │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌────────────────┐         ┌────────────────┐          │
│  │ Booking Service│         │  Inventory Svc │          │
│  ├────────────────┤         ├────────────────┤          │
│  │ Owns:          │         │ Owns:          │          │
│  │ • bookings     │         │ • inventory    │          │
│  │ • booking_hist │         │ • reservations │          │
│  │                │         │                │          │
│  │ Access via:    │         │ Access via:    │          │
│  │ REST API only  │         │ REST API only  │          │
│  └────────────────┘         └────────────────┘          │
│                                                         │
│  ┌────────────────┐         ┌────────────────┐          │
│  │ Payment Service│         │Notification Svc│          │
│  ├────────────────┤         ├────────────────┤          │
│  │ Owns:          │         │ Owns:          │          │
│  │ • payments     │         │ • notifications│          │
│  │ • transactions │         │ • notify_log   │          │
│  │                │         │                │          │
│  │ Access via:    │         │ Access via:    │          │
│  │ REST API only  │         │ Kafka events   │          │
│  └────────────────┘         └────────────────┘          │
│                                                         │
│  ┌────────────────┐                                     │
│  │Orchestration   │                                     │
│  ├────────────────┤                                     │
│  │ Owns:          │                                     │
│  │ • saga_inst    │                                     │
│  │ • saga_steps   │                                     │
│  │                │                                     │
│  │ Coordinates:   │                                     │
│  │ All services   │                                     │
│  └────────────────┘                                     │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## Data Entities and Relationships

### Booking Service Domain

**Primary Entity**: `Booking`

```sql
CREATE TABLE bookings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(100) NOT NULL,
    inventory_item_id VARCHAR(100) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    status VARCHAR(50) NOT NULL, -- PENDING, CONFIRMED, COMPLETED, CANCELLED
    total_amount DECIMAL(10, 2) NOT NULL,
    payment_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL, -- Auto-cancel after this time
    cancellation_reason VARCHAR(255),
    idempotency_key VARCHAR(100) UNIQUE,
    version BIGINT NOT NULL DEFAULT 0 -- Optimistic locking
);

CREATE INDEX idx_bookings_user_id ON bookings(user_id);
CREATE INDEX idx_bookings_status ON bookings(status);
CREATE INDEX idx_bookings_expires_at ON bookings(expires_at) WHERE status = 'PENDING';
```

**Relationships**:
- `inventory_item_id` → External reference to Inventory Service (via API)
- `payment_id` → External reference to Payment Service (via API)
- `user_id` → External reference to User Service (assumed external)

---

### Inventory Service Domain

**Primary Entity**: `Inventory`

```sql
CREATE TABLE inventory (
    item_id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    total_capacity INTEGER NOT NULL CHECK (total_capacity >= 0),
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity INTEGER NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    price DECIMAL(10, 2) NOT NULL,
    event_date TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0, -- Optimistic locking
    CONSTRAINT check_capacity CHECK (available_quantity + reserved_quantity <= total_capacity)
);

CREATE TABLE inventory_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id VARCHAR(100) NOT NULL REFERENCES inventory(item_id),
    booking_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    status VARCHAR(50) NOT NULL, -- RESERVED, CONFIRMED, RELEASED, EXPIRED
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_inventory_reservations_item_id ON inventory_reservations(item_id);
CREATE INDEX idx_inventory_reservations_booking_id ON inventory_reservations(booking_id);
CREATE INDEX idx_inventory_reservations_status ON inventory_reservations(status);
```

**Invariant**: `available_quantity >= 0` (enforced by database constraint)

---

### Payment Service Domain

**Primary Entity**: `Payment`

```sql
CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(50) NOT NULL, -- PENDING, AUTHORIZED, CAPTURED, REFUNDED, FAILED
    payment_method VARCHAR(50) NOT NULL, -- credit_card, debit_card, paypal
    external_transaction_id VARCHAR(255), -- Gateway transaction ID
    gateway_response TEXT, -- Full gateway response (JSON)
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    idempotency_key VARCHAR(100) UNIQUE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX idx_payments_booking_txn ON payments(booking_id, external_transaction_id) 
WHERE status = 'CAPTURED';

CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL REFERENCES payments(id),
    type VARCHAR(50) NOT NULL, -- AUTHORIZE, CAPTURE, REFUND, VOID
    status VARCHAR(50) NOT NULL, -- SUCCESS, FAILED, PENDING
    amount DECIMAL(10, 2) NOT NULL,
    gateway_request TEXT, -- Request payload
    gateway_response TEXT, -- Response payload
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_transactions_payment_id ON payment_transactions(payment_id);
```

**Relationships**:
- `booking_id` → External reference to Booking Service (via API)
- `external_transaction_id` → Reference to Payment Gateway (external)

---

### Orchestration Service Domain

**Primary Entity**: `SagaInstance`

```sql
CREATE TABLE saga_instances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    saga_type VARCHAR(100) NOT NULL, -- booking_payment_saga
    booking_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL, -- STARTED, COMPENSATING, COMPLETED, FAILED
    current_step INTEGER NOT NULL DEFAULT 0,
    payload JSONB NOT NULL, -- Full saga context
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_saga_instances_status ON saga_instances(status);
CREATE INDEX idx_saga_instances_booking_id ON saga_instances(booking_id);

CREATE TABLE saga_steps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    saga_id UUID NOT NULL REFERENCES saga_instances(id),
    step_number INTEGER NOT NULL,
    step_name VARCHAR(100) NOT NULL, -- reserve_inventory, authorize_payment, etc.
    status VARCHAR(50) NOT NULL, -- PENDING, COMPLETED, FAILED, COMPENSATED
    service_name VARCHAR(100) NOT NULL,
    request_payload JSONB,
    response_payload JSONB,
    error_message TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);

CREATE INDEX idx_saga_steps_saga_id ON saga_steps(saga_id);
CREATE INDEX idx_saga_steps_status ON saga_steps(status);
```

---

## Data Flow Patterns

### Pattern 1: Write Path (Create Booking)

```
┌──────┐
│Client│
└──┬───┘
   │
   │ 1. POST /bookings
   │ { userId, itemId, quantity }
   ▼
┌─────────────┐
│ API Gateway │
└──────┬──────┘
       │
       │ 2. Validate JWT, Rate Limit
       ▼
┌──────────────┐
│Booking Svc   │
└──────┬───────┘
       │
       │ 3. Write to bookings table (PostgreSQL)
       │    [ACID transaction, strong consistency]
       ▼
┌──────────────┐
│booking_db    │ ✅ Booking record persisted
└──────┬───────┘
       │
       │ 4. Trigger Saga
       ▼
┌──────────────┐
│Orchestrator  │
└──────┬───────┘
       │
       ├─────────────────────────────────────────┐
       │                                         │
       │ 5a. REST: Reserve Inventory             │ 5b. REST: Authorize Payment
       ▼                                         ▼
┌──────────────┐                         ┌──────────────┐
│Inventory Svc │                         │Payment Svc   │
└──────┬───────┘                         └──────┬───────┘
       │                                        │
       │ 6a. Update inventory table             │ 6b. Call Payment Gateway
       │     [Optimistic lock]                  │     [External HTTP]
       ▼                                        ▼
┌──────────────┐                         ┌──────────────┐
│inventory_db  │                         │payment_gateway│
└──────┬───────┘                         └──────┬───────┘
       │                                        │
       │ 7a. Return: SUCCESS                    │ 7b. Return: AUTHORIZED
       ▼                                        ▼
┌──────────────┐                         ┌──────────────┐
│Orchestrator  │◄────────────────────────┤Payment Svc   │
└──────┬───────┘                         └──────────────┘
       │
       │ 8. All steps complete
       ▼
┌──────────────┐
│Booking Svc   │
└──────┬───────┘
       │
       │ 9. Update booking status = CONFIRMED
       │    [ACID transaction]
       ▼
┌──────────────┐
│booking_db    │ ✅ Booking confirmed
└──────┬───────┘
       │
       │ 10. Publish Kafka Event
       ▼
┌──────────────┐
│ Kafka Topic  │
│booking-events│
└──────┬───────┘
       │
       │ 11. Async consumption
       ▼
┌──────────────┐
│Notification  │
└──────┬───────┘
       │
       │ 12. Send Email
       ▼
┌──────────────┐
│SendGrid/SMTP │
└──────────────┘
```

**Data Consistency**:
- **Step 3**: Strong consistency (PostgreSQL ACID)
- **Step 6a, 6b**: Strong consistency within each service
- **Step 10-12**: Eventual consistency (async event)

**Timing**:
- Steps 1-9: **Synchronous** (~3-5 seconds)
- Steps 10-12: **Asynchronous** (~1-3 seconds additional)

---

### Pattern 2: Read Path (Query Booking)

```
┌──────┐
│Client│
└──┬───┘
   │
   │ 1. GET /bookings/{id}
   ▼
┌─────────────┐
│ API Gateway │
└──────┬──────┘
       │
       │ 2. Route to Booking Service
       ▼
┌──────────────┐
│Booking Svc   │
└──────┬───────┘
       │
       │ 3. Query booking (PostgreSQL)
       ▼
┌──────────────┐
│booking_db    │ ✅ Read from primary or replica
└──────┬───────┘
       │
       │ 4. Return booking data
       ▼
┌──────────────┐
│Booking Svc   │
└──────┬───────┘
       │
       │ 5. Enrich with inventory details (optional)
       │    [REST call to Inventory Service]
       ▼
┌──────────────┐
│Inventory Svc │
└──────┬───────┘
       │
       │ 6. Check Redis cache
       ▼
┌──────────────┐
│Redis Cache   │
└──────┬───────┘
       │
       ├─────────────┬──────────────┐
       │             │              │
       ▼             ▼              ▼
     HIT           MISS          Query DB
  Return data   Query DB       Update cache
  (~5ms)        (~20ms)        Return data
       │             │              │
       └─────────────┴──────────────┘
       │
       │ 7. Return inventory data
       ▼
┌──────────────┐
│Booking Svc   │
└──────┬───────┘
       │
       │ 8. Combine data
       │    { booking: {...}, inventory: {...} }
       ▼
┌──────┐
│Client│ ✅ Response with enriched data
└──────┘
```

**Performance Optimization**:
- **Database Read Replicas**: Offload read queries (eventual consistency acceptable)
- **Redis Cache**: 80% cache hit rate for hot inventory
- **Response Caching**: Cache entire response at API Gateway (1-minute TTL)

---

### Pattern 3: Event-Driven Data Propagation

```
┌──────────────────────────────────────────────────────────┐
│              Event Producers                             │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  Booking Service    Payment Service    Inventory Service │
│        │                  │                    │         │
│        │ BookingCreated   │ PaymentCaptured    │ InvReserved
│        │                  │                    │         │
│        └──────────────────┴────────────────────┘         │
│                           │                              │
│                           ▼                              │
│                  ┌────────────────┐                      │
│                  │  Kafka Broker  │                      │
│                  │                │                      │
│                  │ • booking-events (12 partitions)      │
│                  │ • payment-events (12 partitions)      │
│                  │ • inventory-events (12 partitions)    │
│                  │                │                      │
│                  │ Retention: 7 days                     │
│                  │ Replication: 3x                       │
│                  └────────┬───────┘                      │
│                           │                              │
│         ┌─────────────────┼─────────────────┐            │
│         │                 │                 │            │
│         ▼                 ▼                 ▼            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐       │
│  │Notification │  │ Analytics   │  │ Audit Log   │       │
│  │  Service    │  │  Service    │  │  Service    │       │
│  │             │  │             │  │             │       │
│  │ • Send Email│  │ • Revenue   │  │ • Compliance│       │
│  │ • Send SMS  │  │ • Reporting │  │ • Forensics │       │
│  └─────────────┘  └─────────────┘  └─────────────┘       │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

**Event Schema Example** (Avro):

```json
{
  "namespace": "com.booking.events",
  "type": "record",
  "name": "BookingCreated",
  "fields": [
    {"name": "eventId", "type": "string"},
    {"name": "bookingId", "type": "string"},
    {"name": "userId", "type": "string"},
    {"name": "itemId", "type": "string"},
    {"name": "quantity", "type": "int"},
    {"name": "totalAmount", "type": "double"},
    {"name": "status", "type": "string"},
    {"name": "timestamp", "type": "long"}
  ]
}
```

**Event Ordering Guarantee**:
- **Partition Key**: `bookingId` → All events for same booking go to same partition
- **Within Partition**: Strict ordering (event A before event B)
- **Across Partitions**: No ordering guarantee

---

## Consistency Models

### Strong Consistency (Within Service Boundary)

**Where**: Single database transactions (PostgreSQL ACID)

**Example**:
```java
@Transactional
public Booking createBooking(BookingRequest request) {
    // All operations in single transaction
    Booking booking = new Booking(request);
    bookingRepository.save(booking); // Write to bookings table
    
    BookingHistory history = new BookingHistory(booking);
    historyRepository.save(history); // Write to booking_history table
    
    // Both writes commit atomically
    return booking;
}
```

**Guarantee**: Either both writes succeed or both rollback. No intermediate state visible.

---

### Eventual Consistency (Across Service Boundaries)

**Where**: Cross-service data via Kafka events

**Example**:
```
Time T0: Booking confirmed (booking_db updated)
Time T1: Event published to Kafka (~10ms later)
Time T2: Notification service consumes event (~50ms later)
Time T3: Email sent to user (~2s later)

Total Lag: ~2 seconds (eventual consistency window)
```

**Guarantee**: Data will eventually be consistent across services, but not immediately.

---

### Read-Your-Writes Consistency

**Where**: User queries their own recent bookings

**Implementation**:
- **Write to Primary**: All writes go to primary database
- **Read from Primary**: Recent queries (<5 seconds) also read from primary
- **Read from Replica**: Older queries (>5 seconds) can use read replicas

```java
public Booking getBooking(String bookingId, String userId) {
    Booking booking = bookingRepository.findById(bookingId);
    
    // Check if booking was just created (< 5 seconds ago)
    if (Duration.between(booking.getCreatedAt(), Instant.now()).getSeconds() < 5) {
        // Force read from primary to ensure consistency
        return bookingRepository.findByIdFromPrimary(bookingId);
    }
    
    // Older booking, safe to read from replica
    return booking;
}
```

---

## Data Caching Strategy

### Cache Hierarchy

```
┌─────────────────────────────────────────────────────────┐
│                    L1: Application Cache                │
│                    (Caffeine, 1-minute TTL)             │
│                    Hot Data: Top 100 items              │
│                    Size: 100 MB per instance            │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼ (Cache Miss)
┌─────────────────────────────────────────────────────────┐
│                    L2: Distributed Cache                │
│                    (Redis Cluster)                      │
│                    Hot Data: Top 10k items              │
│                    TTL: 5 minutes                       │
│                    Size: 10 GB total                    │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼ (Cache Miss)
┌─────────────────────────────────────────────────────────┐
│                    L3: Database Read Replica            │
│                    (PostgreSQL Replica)                 │
│                    Replication Lag: < 1 second          │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼ (Replica Lag Too High)
┌─────────────────────────────────────────────────────────┐
│                    L4: Database Primary                 │
│                    (PostgreSQL Primary)                 │
│                    Source of Truth                      │
└─────────────────────────────────────────────────────────┘
```

---

### Cache Invalidation Strategies

#### Write-Through Cache (Inventory)

```java
public void updateInventory(String itemId, int quantityChange) {
    // 1. Update database
    Inventory inventory = inventoryRepository.findById(itemId);
    inventory.updateQuantity(quantityChange);
    inventoryRepository.save(inventory);
    
    // 2. Update cache immediately
    redisTemplate.opsForValue().set(
        "inventory:" + itemId,
        serialize(inventory),
        Duration.ofMinutes(5)
    );
    
    // 3. Invalidate L1 cache on all instances via pub/sub
    redisPubSub.publish("cache-invalidate", "inventory:" + itemId);
}
```

#### Time-Based Expiration (Bookings)

```java
public Booking getBooking(String bookingId) {
    // Check cache
    String cached = redisTemplate.opsForValue().get("booking:" + bookingId);
    if (cached != null) {
        return deserialize(cached);
    }
    
    // Cache miss, query database
    Booking booking = bookingRepository.findById(bookingId);
    
    // Cache with TTL
    redisTemplate.opsForValue().set(
        "booking:" + bookingId,
        serialize(booking),
        Duration.ofMinutes(10) // Auto-expire after 10 minutes
    );
    
    return booking;
}
```

---

## Data Reconciliation

### Nightly Reconciliation Job

**Purpose**: Detect and fix data inconsistencies across services

```sql
-- Example: Find bookings with mismatched payment status
WITH booking_status AS (
    SELECT id, status, payment_id
    FROM bookings
    WHERE status = 'CONFIRMED'
),
payment_status AS (
    SELECT id, status
    FROM payments
    WHERE status != 'CAPTURED'
)
SELECT b.id AS booking_id, b.status AS booking_status, p.status AS payment_status
FROM booking_status b
JOIN payment_status p ON b.payment_id = p.id;

-- Result: Bookings marked CONFIRMED but payment not CAPTURED
-- Action: Manual review or automated compensation
```

**Reconciliation Frequency**:
- **Real-time**: Circuit breaker monitors (immediate)
- **Hourly**: Orphaned payment detection (< 1 hour old)
- **Daily**: Full data consistency audit (all records)
- **Weekly**: Historical data integrity checks (archive consistency)

---

## Data Volume Projections

### Year 1 (100M Bookings)

| Entity | Records | Size per Record | Total Size |
|--------|---------|-----------------|------------|
| Bookings | 100M | 500 bytes | 50 GB |
| Booking History | 300M | 300 bytes | 90 GB |
| Inventory | 100K | 1 KB | 100 MB |
| Inventory Reservations | 200M | 200 bytes | 40 GB |
| Payments | 100M | 600 bytes | 60 GB |
| Payment Transactions | 300M | 400 bytes | 120 GB |
| Saga Instances | 100M | 1 KB | 100 GB |
| Saga Steps | 500M | 500 bytes | 250 GB |
| **Total** | **~1.3B records** | | **~710 GB** |

### Year 3 (500M Bookings)

| Entity | Records | Total Size |
|--------|---------|------------|
| Bookings | 500M | 250 GB |
| Booking History | 1.5B | 450 GB |
| Inventory | 500K | 500 MB |
| Inventory Reservations | 1B | 200 GB |
| Payments | 500M | 300 GB |
| Payment Transactions | 1.5B | 600 GB |
| Saga Instances | 500M | 500 GB |
| Saga Steps | 2.5B | 1.25 TB |
| **Total** | **~6.5B records** | **~3.5 TB** |

**Scaling Strategy**:
- **Year 1-2**: Vertical scaling + read replicas
- **Year 3+**: Horizontal sharding by `user_id` hash

---

## Data Lineage and Audit Trail

### Event Sourcing for Audit

All state changes captured as events:

```
Booking Lifecycle:
1. BookingCreatedEvent (T0)
2. InventoryReservedEvent (T0 + 50ms)
3. PaymentAuthorizedEvent (T0 + 2s)
4. BookingConfirmedEvent (T0 + 3s)
5. PaymentCapturedEvent (T0 + 5s)
6. BookingCompletedEvent (T0 + 7 days)

All events stored in Kafka (7-day retention) + Archived to S3 (7-year retention)
```

**Compliance**: PCI-DSS, SOX, GDPR audit requirements

---

## Related Documents

- [High-Level Architecture](../high-level/) - System structure
- [Sequence Diagrams](../sequence-diagrams/) - Interaction flows
- [Trade-offs](../../docs/trade-offs.md) - Data consistency trade-offs
- [Problem Statement](../../docs/problem-statement.md) - Data requirements
