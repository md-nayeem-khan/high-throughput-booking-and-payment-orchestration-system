# Trade-offs and Architectural Decisions

## Purpose

This document captures key architectural decisions, alternatives considered, rationale for choices, and acknowledged trade-offs. Each decision is presented with context to demonstrate mature system design thinking.

---

## Decision 1: Saga Pattern vs. Two-Phase Commit (2PC)

### Context

Distributed transactions must coordinate booking, inventory, and payment services. Need atomicity without sacrificing availability.

### Decision

**Chosen: Saga Pattern (Orchestration-based)**

### Alternatives Considered

#### Option A: Two-Phase Commit (2PC)
- **Pros**: Strong consistency, ACID guarantees across services
- **Cons**: 
  - Blocking protocol (reduces availability)
  - Single coordinator is a single point of failure
  - Poor performance under high latency
  - Locks held during coordination (reduced throughput)

#### Option B: Saga Pattern (Choreography-based)
- **Pros**: No central coordinator, fully decentralized
- **Cons**: 
  - Complex to trace and debug
  - Difficult to reason about overall flow
  - Cyclic dependencies risk
  - No single view of saga state

#### Option C: Event Sourcing with CQRS
- **Pros**: Complete audit trail, time-travel debugging
- **Cons**: 
  - Steep learning curve
  - Event schema evolution complexity
  - Eventual consistency on read side
  - Overkill for current requirements

### Why We Chose Orchestration-based Saga

1. **Scalability**: No distributed locks, services remain independent
2. **Availability**: Failure of one service doesn't block others
3. **Visibility**: Central orchestrator provides single view of saga state
4. **Debugging**: Easier to trace flow through orchestrator logs
5. **Industry Adoption**: Proven pattern at Uber, Amazon, Netflix

### Trade-offs Accepted

| Aspect | Trade-off |
|--------|-----------|
| **Consistency** | Eventual consistency (typically < 5s) vs. immediate consistency |
| **Complexity** | Compensation logic for each service vs. automatic rollback |
| **Development** | More code (compensating transactions) vs. simpler code |
| **Debugging** | Must trace distributed saga state vs. single transaction log |

### Mitigations

- **Saga State Machine**: Explicit states (PENDING, COMPENSATING, COMPLETED, FAILED)
- **Idempotent Operations**: All saga steps can be retried safely
- **Timeout Handling**: Automatic compensation if steps don't complete in 30s
- **Monitoring**: Grafana dashboard for saga success/failure rates

---

## Decision 2: Optimistic Locking vs. Pessimistic Locking

### Context

Prevent inventory overselling under high concurrency without sacrificing performance.

### Decision

**Chosen: Optimistic Locking with Version Numbers**

### Alternatives Considered

#### Option A: Pessimistic Locking (SELECT FOR UPDATE)
- **Pros**: Guaranteed consistency, prevents lost updates
- **Cons**: 
  - Serializes concurrent requests (bottleneck)
  - Deadlock risk
  - Poor scalability under high concurrency

#### Option B: Distributed Locks (Redis/Zookeeper)
- **Pros**: Strong coordination across service instances
- **Cons**: 
  - Additional infrastructure dependency
  - Lock contention under high load
  - Potential for lock expiry issues
  - Reduced availability if lock service fails

#### Option C: Database-level Serializable Isolation
- **Pros**: Maximum consistency guarantees
- **Cons**: 
  - Performance penalty (transaction retries)
  - Not supported well by all databases
  - Reduces throughput significantly

### Why We Chose Optimistic Locking

1. **Performance**: No locks held during business logic execution
2. **Scalability**: Horizontal scaling without coordination overhead
3. **Simplicity**: Built into JPA/Hibernate (`@Version`)
4. **Failure Mode**: Contention results in retry, not deadlock

### Trade-offs Accepted

| Aspect | Trade-off |
|--------|-----------|
| **Contention** | High contention → more retries vs. blocking waits |
| **Success Rate** | 2-5% retry rate under peak load vs. 100% first-attempt success |
| **Complexity** | Client must handle retry logic vs. transparent blocking |


### Mitigations

- **Retry Strategy**: Exponential backoff with jitter (max 3 retries)
- **Circuit Breaker**: Fail fast if retry rate exceeds 50%
- **Monitoring**: Alert if contention rate > 10%
- **Hybrid Approach**: Use Redis lock for flash sales (rare, high-contention scenarios)

---

## Decision 3: Kafka vs. RabbitMQ for Event Streaming

### Context

Need asynchronous communication for events (booking created, payment completed) with high throughput and durability.

### Decision

**Chosen: Apache Kafka**

### Alternatives Considered

#### Option A: RabbitMQ
- **Pros**: Mature, flexible routing, easy to learn
- **Cons**: 
  - Lower throughput (10k msg/s vs 1M msg/s)
  - Complex clustering setup
  - Not designed for event streaming (more for task queues)

#### Option B: AWS SQS/SNS
- **Pros**: Fully managed, no infrastructure maintenance
- **Cons**: 
  - Vendor lock-in
  - Higher latency (P99 ~ 1s)
  - Limited throughput per queue
  - Cost at scale

#### Option C: Redis Streams
- **Pros**: Low latency, simple setup
- **Cons**: 
  - Not designed for multi-consumer patterns
  - Limited durability guarantees
  - In-memory constraints

### Why We Chose Kafka

1. **Throughput**: Handles 1M+ messages/second per broker
2. **Durability**: Persistent log with configurable retention
3. **Replay**: Consumers can reprocess events from any offset
4. **Ordering**: Partition-level ordering guarantees
5. **Ecosystem**: Integration with Connect, Streams, Schema Registry

### Trade-offs Accepted

| Aspect | Trade-off |
|--------|-----------|
| **Complexity** | Steep learning curve vs. simpler alternatives |
| **Operations** | ZooKeeper dependency (pre-KRaft) vs. single-node brokers |
| **Latency** | P99 ~10ms vs. P99 ~1ms (Redis) |
| **Resource Usage** | Higher memory footprint vs. lightweight alternatives |

### Mitigations

- **Managed Service**: Use Confluent Cloud or AWS MSK (reduce ops burden)
- **Schema Registry**: Avro schemas for backward compatibility
- **Consumer Groups**: Automatic rebalancing and parallelism
- **Monitoring**: Kafka lag monitoring with alerts

---

## Decision 4: Synchronous REST vs. Fully Asynchronous Event-Driven

### Context

Should services communicate via REST APIs or purely through events?

### Decision

**Chosen: Hybrid Approach (REST for Commands, Events for State Changes)**

### Alternatives Considered

#### Option A: Fully Synchronous REST
- **Pros**: Simple request-response, easy to reason about
- **Cons**: 
  - Tight coupling between services
  - Cascading failures
  - Synchronous latency accumulation

#### Option B: Fully Asynchronous Event-Driven
- **Pros**: Maximum decoupling, resilience
- **Cons**: 
  - Complex to trace end-to-end flow
  - Eventual consistency everywhere
  - Difficult to return immediate responses to clients

#### Option C: GraphQL Federation
- **Pros**: Unified API, flexible queries
- **Cons**: 
  - Overkill for backend-to-backend
  - Resolver complexity
  - N+1 query problem

### Why We Chose Hybrid

1. **Client Experience**: REST provides immediate booking confirmation
2. **Decoupling**: Events prevent tight coupling for non-critical paths
3. **Resilience**: Notification failures don't block booking confirmation
4. **Pragmatism**: Industry standard pattern (Uber, Netflix, Airbnb)

### Communication Patterns

| Use Case | Pattern | Rationale |
|----------|---------|-----------|
| Booking Creation | Synchronous REST | User needs immediate response |
| Inventory Reservation | Synchronous REST | Must confirm availability before proceeding |
| Payment Authorization | Synchronous REST | User waits for payment result |
| Notification | Asynchronous Event | Not critical path, can be retried |
| Analytics | Asynchronous Event | No real-time requirement |
| Saga Compensation | Asynchronous Event | Long-running, retry-friendly |

### Trade-offs Accepted

- **Mixed Mental Model**: Developers must understand both REST and events
- **Increased Complexity**: Two communication mechanisms to maintain
- **Partial Asynchrony**: Not fully reactive architecture

### Mitigations

- **Clear Guidelines**: Document when to use REST vs. events
- **API Gateway**: Centralized entry point for all synchronous calls
- **Correlation IDs**: Trace requests across REST and Kafka boundaries

---

## Decision 5: PostgreSQL vs. NoSQL Databases

### Context

Need to store booking, inventory, and payment data with transactional guarantees.

### Decision

**Chosen: PostgreSQL (Relational Database)**

### Alternatives Considered

#### Option A: MongoDB (Document Store)
- **Pros**: Flexible schema, horizontal scaling
- **Cons**: 
  - Weaker consistency guarantees (until v4)
  - No JOIN support (application-level joins)
  - Learning curve for developers

#### Option B: Cassandra (Wide-Column Store)
- **Pros**: Massive write throughput, multi-datacenter replication
- **Cons**: 
  - No ACID transactions
  - Eventual consistency by default
  - Complex data modeling

#### Option C: DynamoDB (Managed NoSQL)
- **Pros**: Fully managed, predictable latency
- **Cons**: 
  - Vendor lock-in
  - No cross-partition transactions (initially)
  - Cost at scale

### Why We Chose PostgreSQL

1. **ACID Transactions**: Full consistency within service boundary
2. **Maturity**: Battle-tested, 30+ years of production use
3. **Features**: JOINs, foreign keys, constraints, triggers
4. **Ecosystem**: Rich tooling (Flyway, pgAdmin, monitoring)
5. **Developer Familiarity**: SQL is universal skill

### Trade-offs Accepted

| Aspect | Trade-off |
|--------|-----------|
| **Horizontal Scaling** | Vertical scaling + read replicas vs. automatic sharding |
| **Schema Changes** | Requires migrations vs. schemaless flexibility |
| **Write Throughput** | 10k writes/sec per node vs. 100k+ (Cassandra) |

### Scaling Strategy

- **Year 1**: Single primary + 2 read replicas (sufficient for 100M bookings)
- **Year 2**: Table partitioning by date (booking_2026_01, booking_2026_02)
- **Year 3**: Shard by user_id hash if single node insufficient

### Mitigations

- **Connection Pooling**: HikariCP with 20 connections per instance
- **Read Replicas**: Offload analytics and reporting queries
- **Caching**: Redis for hot inventory queries (80% cache hit rate)
- **Indexing**: Composite indexes on (userId, createdAt), (itemId, status)

---

## Decision 6: Idempotency Implementation Strategy

### Context

Must handle duplicate requests from client retries without creating duplicate bookings or payments.

### Decision

**Chosen: Redis-based Idempotency Key Store (24-hour TTL)**

### Alternatives Considered

#### Option A: Database-based Idempotency Table
- **Pros**: Durable, same infrastructure as application data
- **Cons**: 
  - Adds write latency to every request
  - Requires cleanup job for old keys
  - Increases database load

#### Option B: Application-level Caching (Caffeine)
- **Pros**: Fastest performance (in-memory)
- **Cons**: 
  - Lost on service restart
  - Not shared across instances (duplicate processing possible)

#### Option C: No Idempotency Handling
- **Pros**: Simplest implementation
- **Cons**: 
  - Duplicate payments on retry
  - Customer complaints and financial loss

### Why We Chose Redis

1. **Performance**: Sub-millisecond latency for key lookup
2. **TTL Support**: Automatic expiration (no cleanup needed)
3. **Shared State**: All instances see same idempotency keys
4. **Durability**: AOF persistence prevents data loss on restart

### Trade-offs Accepted

| Aspect | Trade-off |
|--------|-----------|
| **Durability** | Redis failure → temporary loss of idempotency vs. database durability |
| **TTL** | 24-hour limit → old keys can't be checked vs. infinite storage |
| **Consistency** | Redis replication lag → brief window of duplicate processing |

### Mitigations

- **Redis Cluster**: Automatic failover with sentinel
- **Database Constraints**: Unique constraints on (bookingId, paymentTxnId) as last resort
- **Monitoring**: Alert if idempotency cache hit rate drops below 10%

---

## Decision 7: Service Decomposition Strategy

### Context

How to split monolith into microservices? What are the service boundaries?

### Decision

**Chosen: Domain-Driven Design (DDD) Bounded Contexts**

Services identified:
1. **Booking Service**: Booking lifecycle management
2. **Inventory Service**: Capacity and availability management
3. **Payment Service**: Payment processing and reconciliation
4. **Orchestration Service**: Saga coordination
5. **Notification Service**: Email/SMS delivery

### Alternatives Considered

#### Option A: Split by Technical Layer
- Services: API Gateway, Business Logic Service, Data Access Service
- **Cons**: Tight coupling, not independently deployable

#### Option B: Monolith with Modular Structure
- Single deployable with clear package boundaries
- **Cons**: Cannot scale services independently, shared database

#### Option C: Finer-Grained Microservices
- 10+ services (User, Auth, Booking, Payment, Inventory, Notification, Audit, Analytics, etc.)
- **Cons**: Over-engineering for current scale, operational overhead

### Why We Chose DDD Bounded Contexts

1. **Business Alignment**: Services map to business capabilities
2. **Team Autonomy**: Each service owned by dedicated team
3. **Independent Deployment**: Services released independently
4. **Technology Flexibility**: Each service can choose optimal tech stack
5. **Failure Isolation**: One service failure doesn't cascade

### Trade-offs Accepted

- **Network Latency**: Inter-service calls add 5-10ms overhead
- **Data Duplication**: Each service may cache data from others
- **Distributed Debugging**: Harder to trace issues across services
- **Deployment Complexity**: More services to monitor and deploy

### Mitigations

- **API Gateway**: Single entry point with routing and rate limiting
- **Service Mesh**: Istio for observability and circuit breaking
- **Correlation IDs**: End-to-end tracing with OpenTelemetry
- **Centralized Logging**: ELK stack for log aggregation

---

## Decision Summary Matrix

| Decision | Choice | Key Benefit | Main Trade-off |
|----------|--------|-------------|----------------|
| Distributed Transactions | Saga Pattern | Scalability | Eventual consistency |
| Concurrency Control | Optimistic Locking | Performance | Retry on contention |
| Messaging | Kafka | Throughput | Operational complexity |
| Communication | Hybrid (REST + Events) | Pragmatism | Mixed patterns |
| Database | PostgreSQL | ACID guarantees | Vertical scaling |
| Idempotency | Redis | Performance | Durability risk |
| Service Boundaries | DDD Bounded Contexts | Business alignment | Network overhead |

---

## Decision Review Process

Architectural decisions are reviewed:
- **Quarterly**: Assess if assumptions still hold
- **On Incident**: Major outages trigger decision review
- **On Requirement Change**: New business needs may invalidate decisions
