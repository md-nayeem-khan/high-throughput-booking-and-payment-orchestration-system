# Failure Scenarios and Recovery Strategies

## Purpose

This document catalogs realistic production failure scenarios, their detection mechanisms, impact analysis, and recovery strategies.

---

## Failure Category 1: Payment Processing Failures

### Scenario 1.1: Payment Gateway Timeout

#### Description
Payment authorization request to external gateway times out after 5 seconds. Client doesn't know if payment succeeded.

#### Root Causes
- Network partition between service and gateway
- Gateway under heavy load (degraded performance)
- DNS resolution failure
- Gateway performing rolling deployment

#### Detection Mechanism
- Circuit breaker detects consecutive timeouts
- Prometheus alert: `payment_gateway_timeout_rate > 5%`
- APM tracing shows incomplete payment spans

#### Impact Assessment
- **User Experience**: User sees "Payment processing" spinner indefinitely
- **Financial Risk**: Unknown payment state (charged but no booking?)
- **Inventory**: Item remains reserved but payment unclear
- **Scale**: Medium (affects individual transactions)

#### Recovery Strategy

**Immediate (Automated):**
1. **Return Idempotent Response**: Return HTTP 202 (Accepted) with `transactionId`
2. **Asynchronous Status Check**: Background job queries gateway status API
3. **Webhook Handling**: Gateway sends async webhook on completion
4. **Client Polling**: Client polls `/payments/{transactionId}/status` endpoint

**Compensation (If Payment Failed):**
- Release inventory reservation
- Update booking status to `CANCELLED`
- Send cancellation notification to user
- Refund if payment captured

**Compensation (If Payment Succeeded):**
- Confirm booking and deduct inventory
- Capture payment (if auth-only)
- Send booking confirmation

#### Prevention Measures
- **Increase Timeout**: Bump to 8s for gateway calls (based on P99.9 latency)
- **Circuit Breaker**: Open circuit after 50% failure rate
- **Bulkhead**: Separate thread pool for gateway calls (prevents exhaustion)
- **Health Check**: Pre-flight check of gateway availability before processing

#### Monitoring & Alerting
```yaml
alert: PaymentGatewayTimeoutSpike
expr: rate(payment_gateway_timeout_total[5m]) > 0.05
severity: critical
description: "Payment gateway timeout rate exceeds 5% ({{ $value }})"
```

---

### Scenario 1.2: Payment Succeeds but Booking Confirmation Fails

#### Description
Payment is successfully captured by gateway, but Booking Service crashes before marking booking as confirmed. User is charged without confirmation.

#### Root Causes
- Booking Service crashes due to OOM or unhandled exception
- Database connection lost after payment but before booking update
- Network partition during saga coordination
- Kafka producer fails to publish `PaymentCompleted` event

#### Detection Mechanism
- **Orphaned Payments**: Cron job detects payments in `CAPTURED` state without corresponding confirmed booking
- **Saga Timeout**: Orchestrator detects saga stuck in `PAYMENT_COMPLETED` state > 30 seconds
- **Customer Complaint**: User reports charge without confirmation email

#### Impact Assessment
- **Financial**: Customer charged incorrectly (chargeback risk)
- **Reputation**: Severe - loss of trust
- **Scale**: Low frequency but high severity

#### Recovery Strategy

**Automated Reconciliation (Every 5 minutes):**

**For Each Orphaned Payment:**
1. **Check Inventory**: Verify if inventory was deducted
2. **Option A (Inventory Deducted)**: 
   - Update booking status to `CONFIRMED`
   - Send delayed confirmation email
   - Log incident for analysis
3. **Option B (Inventory Not Deducted)**:
   - Attempt inventory deduction
   - If successful: Confirm booking
   - If failed: Initiate refund and cancel booking

**Saga-Based Recovery:**

#### Prevention Measures
- **Outbox Pattern**: Atomically write booking + event in same transaction
- **Idempotency**: Booking confirmation is idempotent (can be retried)
- **Health Checks**: Kubernetes readiness probe prevents routing during shutdown
- **Graceful Shutdown**: Finish in-flight requests before pod termination

#### Monitoring & Alerting
```yaml
alert: OrphanedPaymentsDetected
expr: orphaned_payments_count > 0
severity: warning
description: "{{ $value }} payments captured without confirmed bookings"
```

---

### Scenario 1.3: Duplicate Payment Due to Client Retry

#### Description
Network glitch causes client to not receive initial response. Client retries with same payment details, potentially charging customer twice.

#### Root Causes
- Network timeout on client side after server processed request
- Client-side retry logic without idempotency key
- Load balancer routing retry to different instance
- User double-clicking "Pay" button in UI

#### Detection Mechanism
- **Idempotency Key Match**: Redis cache hit on duplicate request
- **Database Unique Constraint**: `UNIQUE(booking_id, payment_txn_id)` violation
- **Same Card/Amount**: Fraud detection flags duplicate charge within 1 minute

#### Impact Assessment
- **User Experience**: Could result in double charge
- **Financial**: Chargeback fees, refund processing costs
- **Scale**: Common (10-20% of requests may be retries)

#### Recovery Strategy

**Prevention (Primary Defense):**

**Fallback (Database Constraint):**

If duplicate slips through:
1. **Database Rejects Insert**: Catch `DataIntegrityViolationException`
2. **Query Original Payment**: Fetch existing payment record
3. **Return Same Response**: Return original payment details to client

#### Prevention Measures
- **Client SDK**: Enforce idempotency key generation in SDK
- **API Gateway**: Reject requests without `Idempotency-Key` header
- **UI Disable**: Disable "Pay" button after first click
- **Database Constraints**: Last line of defense

#### Monitoring & Alerting
```yaml
alert: HighIdempotencyHitRate
expr: idempotency_cache_hit_rate > 0.15
severity: info
description: "{{ $value }}% of requests are retries (elevated client issues?)"
```

---

## Failure Category 2: Inventory Consistency Failures

### Scenario 2.1: Race Condition on Last Item

#### Description
Two users attempt to book the last available item simultaneously. Without proper locking, both bookings could succeed (overselling).

#### Root Causes
- Optimistic locking retry exhausted (high contention)
- Read-before-write race condition
- Stale cache (Redis shows available, DB shows unavailable)
- Replication lag on read replica

#### Detection Mechanism
- **Negative Inventory**: `available_quantity < 0` in database
- **Overselling Alert**: More bookings than capacity
- **Customer Report**: Booking confirmed but later cancelled

#### Impact Assessment
- **Financial**: Overbooking requires upgrades or refunds
- **Reputation**: Critical - customer expectations not met
- **Scale**: Rare under normal load, common during flash sales

#### Recovery Strategy

**Immediate (Prevent Negative Inventory):**

**On Constraint Violation:**

**If Overselling Detected (Compensate):**
1. **Identify Excess Bookings**: Last N bookings created = (bookings - capacity)
2. **Cancellation Strategy**:
   - Cancel bookings in `PENDING` state first
   - Refund payments automatically
   - Offer upgrade or voucher to affected users
3. **Notification**: Apologetic email with compensation offer

#### Prevention Measures
- **Optimistic Locking**: Enabled by default (`@Version`)
- **Circuit Breaker**: Fail fast if contention > 50% for 1 minute
- **Redis Lock (Flash Sales)**: Use distributed lock for high-contention items
- **Queue-Based Booking**: Serialize requests for ultra-hot items

**Hybrid Approach for Flash Sales:**

#### Monitoring & Alerting
```yaml
alert: NegativeInventoryDetected
expr: min(inventory_available_quantity) < 0
severity: critical
description: "Inventory oversold for item {{ $labels.item_id }}"
```

---

### Scenario 2.2: Inventory Reservation Leak (Not Released)

#### Description
Inventory is reserved for booking but never released due to saga compensation failure. Capacity appears lower than actual.

#### Root Causes
- Compensation logic failed to execute (code bug)
- Kafka event lost (broker failure during production)
- Service crashed before publishing compensation event
- Deadlock in saga orchestrator

#### Detection Mechanism
- **Stale Reservations**: Reservations older than 10 minutes still in `RESERVED` state
- **Inventory Drift**: Sum of confirmed bookings != (total - available)
- **Periodic Audit**: Nightly job compares inventory vs. actual bookings

#### Recovery Strategy

**Automated Cleanup (Every 5 minutes):**

**Manual Reconciliation (Nightly):**

#### Prevention Measures
- **Saga Timeout**: Auto-cancel reservations after 10 minutes
- **Dead Letter Queue**: Failed compensations go to DLQ for manual review
- **Idempotent Release**: Releasing already-released reservation is no-op
- **Outbox Pattern**: Reliable event publishing

#### Monitoring & Alerting
```yaml
alert: StaleInventoryReservations
expr: inventory_reservations_stale_count > 10
severity: warning
description: "{{ $value }} inventory reservations not released after 10 minutes"
```

---

## Failure Category 3: Messaging Failures

### Scenario 3.1: Kafka Consumer Crash with Uncommitted Offset

#### Description
Consumer processes message, updates database, but crashes before committing Kafka offset. On restart, message is reprocessed (duplicate).

#### Root Causes
- OOM kill during offset commit
- Container restart by Kubernetes (eviction)
- Network partition before commit completes
- Unhandled exception after business logic

#### Detection Mechanism
- **Duplicate Events**: Same `bookingId` processed twice (detected by idempotency key)
- **Consumer Lag Spike**: Offset rewind after restart
- **Prometheus Metric**: `kafka_consumer_rebalance_total` spike

#### Impact Assessment
- **Data Integrity**: Risk of duplicate bookings or payments
- **Performance**: Wasted CPU reprocessing old messages
- **Scale**: Low frequency but requires defensive coding

#### Recovery Strategy

**Idempotent Consumer (Primary Defense):**

**Transactional Outbox for Exactly-Once:**

#### Prevention Measures
- **Manual Commit**: Only commit after successful processing
- **Transactional Outbox**: Guarantee event published once
- **Database Deduplication**: Store processed event IDs
- **Health Checks**: Graceful shutdown before Kubernetes kill

#### Monitoring & Alerting
```yaml
alert: KafkaConsumerLagIncreasing
expr: kafka_consumer_lag_seconds > 60
severity: warning
description: "Consumer lag {{ $value }}s for group {{ $labels.consumer_group }}"
```

---

### Scenario 3.2: Kafka Broker Failure During Event Production

#### Description
Service attempts to publish `BookingConfirmed` event, but Kafka leader broker is down. Event may be lost.

#### Root Causes
- Kafka broker crash or restart
- Network partition isolating broker
- Disk full on broker node
- ZooKeeper quorum loss (pre-KRaft)

#### Detection Mechanism
- **Producer Exception**: `TimeoutException` or `NotLeaderForPartitionException`
- **Kafka Metrics**: `kafka_broker_offline_partitions_count > 0`
- **APM Tracing**: Incomplete event publishing spans

#### Impact Assessment
- **Eventual Consistency**: Downstream services miss state changes
- **User Experience**: Delayed notifications
- **Scale**: Affects all services during outage

#### Recovery Strategy

**Retry with Backoff:**

**Outbox Polling (Guaranteed Delivery):**

#### Prevention Measures
- **Kafka Cluster**: 3+ brokers with replication factor = 3
- **Acknowledgment**: `acks=all` (wait for all in-sync replicas)
- **Outbox Pattern**: Decouple business logic from event publishing
- **Circuit Breaker**: Stop publishing if Kafka unavailable (prevent resource exhaustion)

#### Monitoring & Alerting
```yaml
alert: KafkaBrokerOffline
expr: kafka_broker_online_count < 2
severity: critical
description: "Only {{ $value }} Kafka brokers online (expected 3)"
```

---

## Failure Category 4: Database Failures

### Scenario 4.1: Database Primary Failover

#### Description
PostgreSQL primary node fails. Automatic failover to standby takes 30-60 seconds. All write operations fail during failover.

#### Root Causes
- Hardware failure (disk, memory)
- Database crash due to OOM
- Network partition (split-brain scenario)
- Planned maintenance without proper coordination

#### Detection Mechanism
- **Connection Pool Exhaustion**: All connections timing out
- **Health Check Failure**: `/actuator/health` returns `DOWN`
- **Database Metrics**: `pg_stat_replication` shows standby promoted
- **APM Alerts**: 100% error rate for database queries

#### Impact Assessment
- **User Experience**: All bookings fail for 30-60 seconds
- **Financial**: Lost revenue during outage
- **Scale**: Affects entire system

#### Recovery Strategy

**Automatic (Handled by Infrastructure):**
1. **Failover Tool**: Patroni/Stolon promotes standby to primary
2. **DNS Update**: Connection string updated to new primary
3. **Application Reconnect**: HikariCP automatically reconnects

**Application-Level Handling:**

**Graceful Degradation:**

#### Prevention Measures
- **Connection Pool**: Configure connection timeout = 5s (fail fast)
- **Health Checks**: Kubernetes doesn't route traffic to unhealthy pods
- **Read Replicas**: Serve read traffic during primary recovery
- **Circuit Breaker**: Open circuit to prevent cascade failures

#### Monitoring & Alerting
```yaml
alert: DatabasePrimaryDown
expr: pg_up{role="primary"} == 0
severity: critical
description: "PostgreSQL primary is down, failover in progress"
```

---

### Scenario 4.2: Database Connection Pool Exhaustion

#### Description
All database connections in pool (20 connections) are in use. New requests wait indefinitely or timeout.

#### Root Causes
- Long-running queries not releasing connections
- Connection leak (not closed in finally block)
- Traffic spike exceeding connection capacity
- Deadlock holding connections indefinitely

#### Detection Mechanism
- **Pool Metrics**: `hikari_pool_pending_threads > 10`
- **Slow API Response**: P99 latency spikes to > 10 seconds
- **Logs**: `SQLTransientConnectionException: Connection is not available`

#### Impact Assessment
- **User Experience**: Requests timeout or slow
- **Scale**: Affects all operations requiring database access

#### Recovery Strategy

**Immediate (Increase Pool Size):**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30  # Increase from 20
      connection-timeout: 5000  # Fail fast (5s)
      leak-detection-threshold: 60000  # Detect leaks > 60s
```

**Find Long-Running Queries:**

**Code Fix (Connection Leak):**

#### Prevention Measures
- **Query Timeout**: Set `spring.jpa.properties.javax.persistence.query.timeout=3000`
- **Connection Timeout**: Fail fast instead of waiting indefinitely
- **Leak Detection**: HikariCP logs connections held > 60 seconds
- **Load Testing**: Identify connection bottlenecks before production

#### Monitoring & Alerting
```yaml
alert: DatabaseConnectionPoolExhaustion
expr: hikari_pool_active_connections / hikari_pool_max_connections > 0.9
severity: warning
description: "Database pool {{ $value }}% utilized"
```

---

## Failure Category 5: Distributed System Failures

### Scenario 5.1: Network Partition (Split Brain)

#### Description
Network partition splits system into two segments. Each segment believes the other is down. Risk of conflicting operations.

#### Root Causes
- Network switch failure
- Misconfigured firewall rules
- Cloud provider network issue
- DDoS attack causing packet loss

#### Detection Mechanism
- **Health Check Failures**: Services can't reach each other
- **Split Vote**: Kubernetes leader election shows multiple leaders
- **Database Replication Lag**: Standby can't reach primary

#### Impact Assessment
- **Consistency**: Risk of split-brain (two primaries)
- **Availability**: Partial outage (only one segment serves traffic)
- **Scale**: System-wide impact

#### Recovery Strategy

**Prevention (Quorum-Based Decisions):**
- **Kubernetes**: Requires quorum for leader election (3+ masters)
- **Kafka**: Requires ISR (In-Sync Replicas) quorum for writes
- **PostgreSQL**: Fencing prevents split-brain (only one primary)

**Detection and Mitigation:**

**Recovery:**
1. **Restore Network**: Fix underlying network issue
2. **Data Reconciliation**: Compare database state across segments
3. **Conflict Resolution**: Manual resolution for conflicting bookings
4. **Resume Normal Operations**: Exit safe mode after verification

#### Prevention Measures
- **Multi-AZ Deployment**: Spread across availability zones
- **Health Checks**: Detect partitions quickly
- **Quorum Systems**: Prevent split-brain scenarios
- **Monitoring**: Track inter-service latency

---

## Failure Summary Matrix

| Category | Scenario | Detection Time | Recovery Time | Automation Level |
|----------|----------|---------------|---------------|------------------|
| Payment | Gateway Timeout | < 5s | 30-60s | Fully Automated |
| Payment | Orphaned Payment | < 5min | 5-10min | Fully Automated |
| Payment | Duplicate Charge | < 1s | Immediate | Fully Automated |
| Inventory | Race Condition | Immediate | Immediate | Fully Automated |
| Inventory | Reservation Leak | < 5min | 10min | Fully Automated |
| Messaging | Consumer Crash | < 30s | < 1min | Fully Automated |
| Messaging | Broker Failure | < 10s | 5-10min | Semi-Automated |
| Database | Primary Failover | < 10s | 30-60s | Infrastructure |
| Database | Pool Exhaustion | < 30s | 5min | Semi-Automated |
| Network | Partition | < 1min | Manual | Manual Resolution |

---

## 🔧 Runbook: Common Recovery Commands

### Check Service Health
```bash
# All services
kubectl get pods -n booking-system

# Specific service logs
kubectl logs -f deployment/booking-service --tail=100

# Health check
curl http://api-gateway:8080/actuator/health
```

### Database Operations
```bash
# Check replication status
psql -c "SELECT * FROM pg_stat_replication;"

# Find long-running queries
psql -c "SELECT pid, query, state, state_change FROM pg_stat_activity WHERE state != 'idle';"

# Connection pool status
curl http://booking-service:8081/actuator/metrics/hikari.connections.active
```

### Kafka Operations
```bash
# Check consumer lag
kafka-consumer-groups --bootstrap-server kafka:9092 --describe --group booking-consumer

# List topics
kafka-topics --bootstrap-server kafka:9092 --list

# Tail events
kafka-console-consumer --bootstrap-server kafka:9092 --topic booking-events --from-beginning
```

---

## Related Documents

- [Trade-offs](trade-offs.md) - Design decisions that affect failure modes
- [Future Improvements](future-improvements.md) - Long-term resilience enhancements
- [Architecture Diagrams](../architecture/) - System structure and dependencies
