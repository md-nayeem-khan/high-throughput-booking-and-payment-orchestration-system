# High-Level Architecture

## Overview

This document describes the high-level architecture of the High-Throughput Booking and Payment Orchestration System, including service boundaries, communication patterns, data flow, and infrastructure components.

---

## System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                            Client Layer                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐                 │
│  │ Web App  │  │ Mobile   │  │ Partner  │  │  Admin   │                 │
│  │ (React)  │  │   App    │  │   API    │  │  Portal  │                 │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘                 │
└───────┼─────────────┼─────────────┼─────────────┼───────────────────────┘
        │             │             │             │
        └─────────────┴─────────────┴─────────────┘
                         │
        ┌────────────────▼────────────────┐
        │  API Gateway (Kong)             │
        │  • Authentication (JWT)         │
        │  • Rate Limiting                │
        │  • Request Routing              │
        │  • Load Balancing               │
        └────────────┬────────────────────┘
                     │
        ┌────────────┴────────────────────────────────────┐
        │                                                 │
        │           Microservices Layer                   │
        │                                                 │
        │  ┌──────────────────┐    ┌──────────────────┐   │
        │  │ Booking Service  │    │ Payment Service  │   │
        │  │ • Create Booking │    │ • Process Payment│   │
        │  │ • Update Status  │    │ • Handle Refunds │   │
        │  │ • Query Bookings │    │ • Reconciliation │   │
        │  │                  │    │                  │   │
        │  │  ┌──────────┐    │    │  ┌──────────┐    │   │
        │  │  │PostgreSQL│    │    │  │PostgreSQL│    │   │
        │  │  │ (Booking)│    │    │  │ (Payment)│    │   │
        │  │  └──────────┘    │    │  └──────────┘    │   │
        │  └────────┬─────────┘    └──────── ┬────────┘   │
        │           │                        │            │
        │  ┌────────▼─────────────────┬──────▼───────── ┐ │
        │  │  Orchestration Service   │                 │ │
        │  │  • Saga Coordination     │                 │ │
        │  │  • Compensation Logic    │                 │ │
        │  │  • State Management      │                 │ │
        │  │                          │                 │ │
        │  │  ┌──────────┐            │                 │ │
        │  │  │PostgreSQL│            │                 │ │
        │  │  │  (Saga)  │            │                 │ │
        │  │  └──────────┘            │                 │ │
        │  └──────────────────────────┴─────────────────┘ │
        │           │                       │             │
        │  ┌────────▼──────────┐    ┌───────▼──────────┐  │
        │  │ Inventory Service │    │ Notification Svc │  │
        │  │ • Check Available │    │ • Send Email     │  │
        │  │ • Reserve/Release │    │ • Send SMS       │  │
        │  │ • Update Capacity │    │ • Push Notify    │  │
        │  │                   │    │                  │  │
        │  │  ┌──────────┐     │    │  ┌──────────┐    │  │
        │  │  │PostgreSQL│     │    │  │PostgreSQL│    │  │
        │  │  │(Inventory)     │    │  │  (Notif) │    │  │
        │  │  └──────────┘     │    │  └──────────┘    │  │
        │  └───────────────────┘    └──────────────────┘  │
        │                                                 │
        └─────────────────────────────────────────────────┘
                     │
        ┌────────────▼────────────────┐
        │   Event Streaming Layer     │
        │  ┌──────────────────────┐   │
        │  │   Apache Kafka       │   │
        │  │ • booking-events     │   │
        │  │ • payment-events     │   │
        │  │ • inventory-events   │   │
        │  │ • notification-queue │   │
        │  └──────────────────────┘   │
        └─────────────────────────────┘
                     │
        ┌────────────▼────────────────┐
        │    Infrastructure Layer     │
        │  ┌────────┐  ┌──────────┐   │
        │  │ Redis  │  │ External │   │
        │  │ Cache  │  │ Payment  │   │
        │  │ & Lock │  │ Gateway  │   │
        │  └────────┘  └──────────┘   │
        └─────────────────────────────┘
                     │
        ┌────────────▼────────────────┐
        │   Observability Stack       │
        │  ┌──────────┐ ┌──────────┐  │
        │  │Prometheus│ │ Grafana  │  │
        │  └──────────┘ └──────────┘  │
        │  ┌──────────┐ ┌──────────┐  │
        │  │   ELK    │ │OpenTelem.│  │
        │  │  Stack   │ │  Jaeger  │  │
        │  └──────────┘ └──────────┘  │
        └─────────────────────────────┘
```

---

## Service Responsibilities

### 1. API Gateway (Kong/Spring Cloud Gateway)

**Purpose**: Single entry point for all external requests

**Responsibilities**:
- **Authentication**: Validate JWT tokens
- **Authorization**: Enforce RBAC policies
- **Rate Limiting**: 100 req/min per user, 10k req/min per IP
- **Request Routing**: Route to appropriate microservice
- **Load Balancing**: Distribute requests across service instances
- **Protocol Translation**: HTTP → gRPC if needed

**Technology**: Kong or Spring Cloud Gateway  
**Scaling**: Horizontal (5-20 instances based on traffic)  
**Database**: None (stateless)

---

### 2. Booking Service

**Purpose**: Manage booking lifecycle from creation to completion

**Responsibilities**:
- **Create Booking**: Validate request, reserve inventory, initiate payment
- **Update Booking**: Transition states (PENDING → CONFIRMED → COMPLETED)
- **Cancel Booking**: Release inventory, trigger refund
- **Query Bookings**: Retrieve user booking history
- **Emit Events**: Publish `BookingCreated`, `BookingConfirmed`, `BookingCancelled`

**Bounded Context**: Booking lifecycle management  
**Data Ownership**: `bookings` table  
**Technology**: Spring Boot, JPA, PostgreSQL  
**API Endpoints**:
- `POST /api/v1/bookings` - Create booking
- `GET /api/v1/bookings/{id}` - Get booking details
- `GET /api/v1/bookings?userId={userId}` - List user bookings
- `PUT /api/v1/bookings/{id}/cancel` - Cancel booking

**Scaling**: Horizontal (10-50 pods)  
**Database**: PostgreSQL with read replicas

---

### 3. Inventory Service

**Purpose**: Maintain available capacity and prevent overselling

**Responsibilities**:
- **Check Availability**: Query available quantity for item
- **Reserve Inventory**: Temporarily hold capacity (optimistic lock)
- **Release Inventory**: Free reserved capacity (on cancellation)
- **Confirm Reservation**: Deduct from available quantity (on payment success)
- **Update Capacity**: Admin operations to change total capacity

**Bounded Context**: Capacity management  
**Data Ownership**: `inventory`, `inventory_reservations` tables  
**Technology**: Spring Boot, JPA with Optimistic Locking  
**Concurrency Control**: `@Version` annotation (optimistic locking)

**API Endpoints**:
- `GET /api/v1/inventory/{itemId}` - Check availability
- `POST /api/v1/inventory/{itemId}/reserve` - Reserve quantity
- `POST /api/v1/inventory/{itemId}/release` - Release reservation
- `POST /api/v1/inventory/{itemId}/confirm` - Confirm reservation

**Scaling**: Horizontal (5-20 pods)  
**Database**: PostgreSQL  
**Caching**: Redis (80% cache hit rate for hot items)

---

### 4. Payment Service

**Purpose**: Process payments with external gateway and ensure idempotency

**Responsibilities**:
- **Authorize Payment**: Initiate payment with gateway (two-phase)
- **Capture Payment**: Finalize charge after booking confirmed
- **Refund Payment**: Process refunds for cancellations
- **Check Status**: Query payment status for reconciliation
- **Idempotency**: Prevent duplicate charges

**Bounded Context**: Payment processing  
**Data Ownership**: `payments`, `payment_transactions` tables  
**Technology**: Spring Boot, Resilience4j (circuit breaker)  
**External Dependency**: Stripe/Braintree payment gateway

**API Endpoints**:
- `POST /api/v1/payments` - Authorize payment
- `POST /api/v1/payments/{id}/capture` - Capture authorized payment
- `POST /api/v1/payments/{id}/refund` - Refund payment
- `GET /api/v1/payments/{id}/status` - Check payment status

**Resilience Patterns**:
- Circuit Breaker: Opens after 50% failure rate
- Retry: 3 attempts with exponential backoff
- Timeout: 5 seconds for gateway calls
- Bulkhead: Dedicated thread pool (10 threads)

**Scaling**: Horizontal (5-15 pods)  
**Database**: PostgreSQL

---

### 5. Orchestration Service

**Purpose**: Coordinate distributed transactions using Saga pattern

**Responsibilities**:
- **Saga Orchestration**: Manage multi-step workflows
- **State Management**: Track saga progress (PENDING → COMPLETED/FAILED)
- **Compensation**: Trigger rollback on failures
- **Recovery**: Resume incomplete sagas on restart
- **Monitoring**: Expose saga metrics and status

**Saga Steps (Happy Path)**:
1. Reserve Inventory
2. Authorize Payment
3. Confirm Booking
4. Capture Payment
5. Send Notification

**Saga Steps (Compensation Path)**:
1. Release Inventory
2. Refund Payment
3. Cancel Booking
4. Send Cancellation Notification

**Technology**: Spring Boot, State Machine (Spring Statemachine)  
**Database**: PostgreSQL (saga state storage)  
**Scaling**: Horizontal (3-10 pods with leader election)

---

### 6. Notification Service

**Purpose**: Send transactional notifications to users

**Responsibilities**:
- **Email**: Booking confirmation, cancellation, payment receipt
- **SMS**: Booking reminder, payment alerts
- **Push Notifications**: Mobile app notifications (future)
- **Queue Management**: Retry failed notifications

**Bounded Context**: Communication  
**Technology**: Spring Boot, Kafka Consumer, SendGrid/Twilio  
**Scaling**: Horizontal (3-10 pods)  
**Database**: PostgreSQL (notification history)  
**Queue**: Kafka `notification-queue` topic

---

## Communication Patterns

### Synchronous (REST)

Used for operations requiring immediate response:
- **Client → API Gateway**: All user-facing requests
- **Booking → Inventory**: Check availability (needs immediate answer)
- **Booking → Payment**: Authorize payment (user waits for result)
- **Orchestration → Services**: Saga step execution

**Protocol**: HTTP/1.1 REST  
**Timeout**: 2-5 seconds  
**Error Handling**: Circuit breaker, retry with backoff

---

### Asynchronous (Kafka Events)

Used for eventual consistency and decoupling:
- **Booking → Notification**: Send confirmation (not critical path)
- **Payment → Analytics**: Track revenue (no immediate need)
- **All Services → Audit**: Log events for compliance
- **Saga Compensation**: Trigger rollbacks

**Topics**:
- `booking-events`: BookingCreated, BookingConfirmed, BookingCancelled
- `payment-events`: PaymentAuthorized, PaymentCaptured, PaymentRefunded
- `inventory-events`: InventoryReserved, InventoryReleased
- `notification-queue`: EmailRequested, SMSRequested

**Delivery Guarantee**: At-least-once  
**Ordering**: Per partition key (bookingId)  
**Retention**: 7 days

---

## Data Architecture

### Database per Service Pattern

Each service owns its data. No shared databases.

| Service | Database | Tables | Size (1M bookings/month) |
|---------|----------|--------|--------------------------|
| Booking | `booking_db` | `bookings`, `booking_history` | 500 GB |
| Inventory | `inventory_db` | `inventory`, `inventory_reservations` | 50 GB |
| Payment | `payment_db` | `payments`, `payment_transactions` | 200 GB |
| Orchestration | `saga_db` | `saga_instances`, `saga_steps` | 100 GB |
| Notification | `notification_db` | `notifications`, `notification_log` | 300 GB |

---

### Caching Strategy

**Redis Cache**:
- **Hot Inventory**: Top 1000 items (95% of queries)
- **Idempotency Keys**: 24-hour TTL
- **Rate Limiting**: Token bucket counters
- **Session Data**: User session state

**Cache Eviction**:
- **Write-Through**: Update cache on every inventory change
- **TTL**: 5-minute expiry for inventory data
- **Invalidation**: Explicit invalidate on admin updates

---

## Security Architecture

### Authentication Flow

```
User Login
  ↓
Identity Provider (OAuth2)
  ↓
JWT Token (15-minute expiry)
  ↓
API Gateway validates JWT
  ↓
Forward to Service with userId
```

---

### Authorization

**RBAC (Role-Based Access Control)**:
- `USER`: Create/view own bookings
- `ADMIN`: View all bookings, update inventory
- `PARTNER`: Create bookings on behalf of users
- `SUPPORT`: View bookings, initiate refunds

**Enforcement Point**: API Gateway checks `roles` claim in JWT

---

### Data Protection

- **TLS 1.3**: All external traffic encrypted
- **mTLS**: Inter-service communication (via service mesh)
- **Database Encryption**: AES-256 at rest
- **Secret Management**: HashiCorp Vault or AWS Secrets Manager
- **PII Masking**: Mask email/phone in logs

---

## Scalability Design

### Horizontal Scaling

All services are stateless and can scale horizontally:
- **API Gateway**: 5-20 instances (based on RPS)
- **Booking Service**: 10-50 instances (write-heavy)
- **Inventory Service**: 5-20 instances (read-heavy with cache)
- **Payment Service**: 5-15 instances (external gateway bottleneck)
- **Notification Service**: 3-10 instances (async, non-blocking)

---

### Database Scaling

**Write Scaling**:
- Vertical scaling (current): 32 vCPU, 128 GB RAM
- Sharding (future): Horizontal sharding by userId hash

**Read Scaling**:
- Read replicas: 2-5 replicas per service
- Caching: Redis for hot data (80% cache hit rate)

---

### Kafka Scaling

- **Partitions**: 12 partitions per topic (allows 12 parallel consumers)
- **Replication**: Factor of 3 (tolerates 2 broker failures)
- **Brokers**: 3 brokers (expandable to 10+)

---

## Deployment Architecture

### Kubernetes Cluster

```
Namespace: booking-system

Deployments:
- api-gateway (5 replicas)
- booking-service (10 replicas)
- inventory-service (5 replicas)
- payment-service (5 replicas)
- orchestration-service (3 replicas)
- notification-service (3 replicas)

StatefulSets:
- postgresql-booking (1 primary + 2 replicas)
- postgresql-inventory (1 primary + 1 replica)
- postgresql-payment (1 primary + 1 replica)
- redis-cluster (6 nodes)
- kafka-cluster (3 brokers + 3 zookeepers)
```

---

### Resource Allocation

| Service | CPU Request | CPU Limit | Memory Request | Memory Limit |
|---------|-------------|-----------|----------------|--------------|
| API Gateway | 500m | 1000m | 512 MB | 1 GB |
| Booking Service | 500m | 1500m | 1 GB | 2 GB |
| Inventory Service | 500m | 1000m | 512 MB | 1 GB |
| Payment Service | 500m | 1500m | 1 GB | 2 GB |
| Orchestration | 500m | 1000m | 512 MB | 1 GB |
| Notification | 250m | 500m | 256 MB | 512 MB |

---

## Observability

### Metrics (Prometheus)

- **RED Metrics**: Rate, Errors, Duration for all APIs
- **Business Metrics**: Booking success rate, payment conversion
- **Infrastructure**: CPU, memory, disk, network
- **Database**: Connection pool utilization, query latency
- **Kafka**: Consumer lag, producer throughput

---

### Logging (ELK Stack)

- **Structured Logs**: JSON format with correlation IDs
- **Log Levels**: ERROR, WARN, INFO, DEBUG
- **Centralized**: Elasticsearch for aggregation
- **Retention**: 30 days (compliance requirement)

---

### Tracing (Jaeger/OpenTelemetry)

- **Trace Coverage**: 100% of requests
- **Span Details**: Service calls, database queries, Kafka events
- **Sampling**: 100% errors, 1% success (cost optimization)

---

## Key Design Principles

1. **Service Autonomy**: Each service independently deployable
2. **Data Ownership**: One service per database (no sharing)
3. **Eventual Consistency**: Accept delays for cross-service data
4. **Idempotency**: All state-changing operations are idempotent
5. **Resilience**: Bulkheads, circuit breakers, retries
6. **Observability**: Trace, log, and meter everything
7. **Security**: Zero trust, encrypt everything, authenticate always

---

## Related Documents

- [Sequence Diagrams](../sequence-diagrams/) - Detailed interaction flows
- [Data Flow](../data-flow/) - Data ownership and propagation
- [Problem Statement](../../docs/problem-statement.md) - Requirements
- [Trade-offs](../../docs/trade-offs.md) - Design decisions
