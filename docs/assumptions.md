# Assumptions

## Purpose

This document explicitly defines the assumptions, constraints, and boundaries of the High-Throughput Booking and Payment Orchestration System. Clear assumptions enable better design decisions and prevent scope creep.

---

## Business Assumptions

### BA1: Booking Model

- **Single Item Booking**: Each booking request is for one inventory item type (no multi-item carts)
- **Quantity Support**: Users can book multiple units of the same item (e.g., 3 hotel rooms)
- **Reservation Duration**: Inventory reservations expire after **5 minutes** if payment not completed
- **Booking Window**: Bookings can be made up to **90 days in advance**
- **Minimum Booking Value**: No minimum order value enforced (configurable per environment)

### BA2: Payment Processing

- **Single Payment Method**: One payment method per booking (no split payments)
- **Currency**: All transactions in **USD** (no multi-currency support)
- **Payment Gateway**: Integration with single external payment provider (Stripe/Braintree pattern)
- **Prepayment Model**: Full payment required upfront before booking confirmation
- **No Stored Cards**: Payment tokens not stored (users enter card details per transaction)
- **Authorization Hold**: Payment gateway supports **authorization + capture** two-phase flow

### BA3: Inventory Management

- **Finite Inventory**: All items have finite capacity (no unlimited inventory)
- **Static Capacity**: Inventory capacity does not change dynamically (admin updates only)
- **Single Location**: No multi-datacenter inventory splitting initially
- **Homogeneous Items**: All units of same inventory item are interchangeable
- **No Overbooking Strategy**: Unlike airlines, system never intentionally oversells

### BA4: User Behavior

- **Authenticated Users**: All booking requests come from authenticated users
- **Single Active Booking**: User can have multiple bookings, but each request is independent
- **Client Retries**: Clients may retry failed requests with same idempotency key
- **Network Reliability**: Clients may experience network failures during transactions

### BA5: Business Rules

- **No Modification**: Bookings cannot be modified (cancel and rebook instead)
- **Cancellation Policy**: Full refund if cancelled > 24 hours before event (simplified)
- **Grace Period**: Payment failures allow 3 retry attempts before cancellation
- **Confirmation Timeout**: Bookings not confirmed within 10 minutes are auto-cancelled

---

## Technical Assumptions

### TA1: Infrastructure

- **Cloud Deployment**: System runs on cloud infrastructure (AWS/GCP/Azure pattern)
- **Container Orchestration**: Kubernetes or equivalent for service orchestration
- **Managed Services**: Using managed PostgreSQL, Kafka, Redis (not self-hosted)
- **Regional Deployment**: Single region initially (future: multi-region)
- **Resource Availability**: Sufficient compute, memory, and storage resources provisioned

### TA2: Data Characteristics

- **Write Pattern**: 60% reads, 40% writes during normal load
- **Peak Load**: 10x normal load during flash sales and promotional events
- **Data Volume**: ~10 million bookings per month
- **Hot Data**: Last 30 days of bookings account for 90% of queries
- **Event Retention**: Kafka events retained for 7 days

### TA3: Message Delivery

- **At-Least-Once Delivery**: Kafka guarantees at-least-once message delivery
- **Idempotent Consumers**: All Kafka consumers must handle duplicate messages
- **Ordering Guarantee**: Events for same booking are ordered (partition key = bookingId)
- **Message Size**: Event payloads < 1MB (typical < 10KB)
- **Consumer Lag**: Acceptable lag < 10 seconds under normal load

### TA4: Database

- **ACID Guarantees**: PostgreSQL provides full ACID compliance within single database
- **No Cross-Database Transactions**: Each service has dedicated database (no 2PC)
- **Connection Pooling**: 20 connections per service instance (configurable)
- **Read Replicas**: Available for query scaling (eventual consistency acceptable)
- **Backup Strategy**: Point-in-time recovery available (15-minute RPO)

### TA5: Consistency Model

- **Strong Consistency**: Within single service boundaries (same database)
- **Eventual Consistency**: Across services via events (acceptable delay: < 5 seconds)
- **Read-Your-Writes**: User sees their own updates immediately (routing consistency)
- **Monotonic Reads**: Queries to read replicas may lag but never go backward in time

### TA6: External Dependencies

- **Payment Gateway Availability**: 99.5% SLA from payment provider
- **Gateway Response Time**: P99 < 3 seconds for payment authorization
- **Rate Limits**: Payment gateway allows 100 requests/second per account
- **Webhook Reliability**: Payment gateway sends webhooks for async status updates
- **Retry Policy**: Gateway accepts retries with idempotency keys

### TA7: Network

- **Latency**: Inter-service latency < 5ms (same data center)
- **Bandwidth**: Sufficient for 10k requests/second peak traffic
- **Timeouts**: Service-to-service calls timeout after 2 seconds
- **Circuit Breaker**: Opens after 50% error rate over 10 requests
- **Load Balancing**: Round-robin with health check-based removal

---

## Security Assumptions

### SA1: Authentication & Authorization

- **Identity Provider**: External IdP (OAuth2/OIDC) handles user authentication
- **JWT Tokens**: Stateless JWT tokens with 15-minute expiry
- **RBAC**: Role-based access control enforced at API Gateway
- **Service Mesh**: mTLS for inter-service communication (Istio/Linkerd pattern)

### SA2: Data Protection

- **TLS**: All external traffic encrypted with TLS 1.3
- **Secrets Management**: Credentials stored in Vault/AWS Secrets Manager
- **PCI Compliance**: No raw card data stored (payment tokens only)
- **Data Masking**: PII masked in logs and monitoring dashboards

### SA3: Threat Model

- **DDoS Protection**: Handled at CDN/WAF layer (Cloudflare/AWS Shield)
- **Rate Limiting**: 100 requests/minute per user, 10k/minute per IP
- **SQL Injection**: Prevented via parameterized queries (JPA)
- **CSRF**: Not applicable (stateless API, no cookies)

---

## Operational Assumptions

### OA1: Deployment

- **Blue-Green Deployment**: Zero-downtime releases via blue-green strategy
- **Database Migrations**: Backward-compatible schema changes only
- **Rollback Capability**: Ability to rollback to previous version within 5 minutes
- **Feature Flags**: Gradual rollout of new features via feature flags

### OA2: Monitoring

- **APM Tools**: Prometheus + Grafana for metrics, ELK for logs
- **Alerting**: PagerDuty/Opsgenie integration for critical alerts
- **On-Call**: 24/7 on-call rotation for production incidents
- **Runbooks**: Documented procedures for common failure scenarios

### OA3: Scaling

- **Auto-Scaling**: Kubernetes HPA scales pods based on CPU/memory
- **Database Scaling**: Vertical scaling for writes, read replicas for reads
- **Cache Scaling**: Redis cluster can add nodes dynamically
- **Kafka Scaling**: Topic partitions can be increased (no decrease)

---

## Explicit Out-of-Scope

### Not Included in Current Design

1. **Refund Processing**
   - Assumption: Refunds handled manually via admin portal
   - Future: Automated refund workflows with payment gateway integration

2. **User Account Management**
   - Assumption: Separate user service manages profiles, preferences
   - Current scope: Booking system accepts userId as input

3. **Fraud Detection**
   - Assumption: Basic rate limiting only
   - Future: ML-based fraud scoring, velocity checks

4. **Multi-Tenancy**
   - Assumption: Single organization deployment
   - Future: Tenant isolation with separate schemas

5. **Compliance Reporting**
   - Assumption: Manual export of data for audits
   - Future: Automated compliance dashboards (SOX, GDPR)

6. **Advanced Analytics**
   - Assumption: Basic operational metrics only
   - Future: Business intelligence, forecasting, ML recommendations

7. **Mobile SDKs**
   - Assumption: REST APIs only, clients implement their own SDKs
   - Future: Official iOS/Android SDKs

8. **Real-Time Inventory Updates**
   - Assumption: Clients poll for inventory changes
   - Future: WebSocket/SSE for push notifications

9. **Geographic Distribution**
   - Assumption: Single region deployment
   - Future: Multi-region active-active with CRDT-based inventory

10. **A/B Testing Framework**
    - Assumption: Feature flags for gradual rollout only
    - Future: Integrated experimentation platform

---

## Load Assumptions

### Normal Load Profile

- **Requests per Second**: 1,000 booking requests/sec
- **Concurrent Users**: 50,000 active users
- **Database QPS**: 5,000 queries/sec (read + write)
- **Kafka Events**: 2,000 events/sec
- **Cache Hit Rate**: 80% for inventory queries

### Peak Load Profile (Flash Sale)

- **Requests per Second**: 10,000 booking requests/sec (10x normal)
- **Concurrent Users**: 500,000 active users
- **Duration**: 15-30 minutes peak duration
- **Success Rate Target**: > 95% (some requests may fail gracefully)
- **Queue Depth**: Up to 50,000 pending requests in Kafka

### Growth Projections

- **Year 1**: 100 million bookings/year
- **Year 2**: 250 million bookings/year (2.5x growth)
- **Year 3**: 500 million bookings/year (5x initial)

---

## Dependency Assumptions

### Upstream Dependencies

| Dependency | Availability SLA | Timeout | Fallback Strategy |
|------------|------------------|---------|-------------------|
| Payment Gateway | 99.5% | 5s | Retry with backoff, circuit breaker |
| Email Service | 99.0% | 10s | Queue for retry, not critical path |
| SMS Service | 99.0% | 10s | Queue for retry, not critical path |
| Identity Provider | 99.9% | 2s | Circuit breaker, cached tokens |

### Downstream Dependencies

- No critical downstream dependencies (system is backend-of-backend)

---

## Testing Assumptions

- **Unit Tests**: Cover business logic, run in < 30 seconds
- **Integration Tests**: Use Testcontainers, run in < 5 minutes
- **Load Tests**: Run nightly against staging environment
- **Chaos Tests**: Weekly Chaos Monkey runs in pre-production
- **Contract Tests**: Verified on every PR to prevent breaking changes

---

## Evolution of Assumptions

This document will be updated as system requirements evolve. Major assumption changes require:

1. **Architecture Review**: Assess impact on design decisions
2. **Stakeholder Approval**: Business and engineering alignment
3. **Documentation Update**: Reflect changes in all related docs
4. **Migration Plan**: If assumption change affects existing system

**Last Updated**: 2026-01-11  
**Next Review**: Quarterly or on major requirement change
