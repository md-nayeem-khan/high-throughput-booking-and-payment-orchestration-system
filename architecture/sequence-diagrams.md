# Sequence Diagrams

## Overview

This document contains detailed sequence diagrams for key workflows in the booking and payment orchestration system. These diagrams illustrate the interactions between services, timing constraints, and failure handling mechanisms.

---

## Diagram 1: Happy Path - Successful Booking and Payment

### Scenario
User creates a booking, inventory is available, payment succeeds, booking is confirmed.

### Sequence Diagram

```
┌──────┐  ┌─────────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐  ┌──────────┐
│Client│  │   API   │  │ Booking │  │Orchestrator  │Inventory│  │ Payment │  │ External │
│      │  │ Gateway │  │ Service │  │  Service     │ Service │  │ Service │  │ Gateway  │
└──┬───┘  └────┬────┘  └─────┬───┘  └──────┬────┘  └─────┬───┘  └──────┬──┘  └───────┬──┘
   │           │             │             │             │             │             │
   │ POST /bookings          │             │             │             │             │
   │ + Idempotency-Key       │             │             │             │             │
   ├──────────>│             │             │             │             │             │
   │           │             │             │             │             │             │
   │           │ Validate JWT│             │             │             │             │
   │           │ Check Rate Limit          │             │             │             │
   │           │             │             │             │             │             │
   │           │ CreateBooking()           │             │             │             │
   │           ├────────────>│             │             │             │             │
   │           │             │             │             │             │             │
   │           │             │ Check Cache (Idempotency Key)           │             │
   │           │             │ [Cache Miss]│             │             │             │
   │           │             │             │             │             │             │
   │           │             │ Save Booking (Status=PENDING)           │             │
   │           │             │ [DB Transaction]          │             │             │
   │           │             │             │             │             │             │
   │           │             │ StartSaga() │             │             │             │
   │           │             ├────────────>│             │             │             │
   │           │             │             │             │             │             │
   │           │             │             │ Save Saga Instance        │             │
   │           │             │             │ (State=STARTED)           │             │
   │           │             │             │             │             │             │
   │           │             │             │ Step 1: Reserve Inventory │             │
   │           │             │             ├────────────>│             │             │
   │           │             │             │             │             │             │
   │           │             │             │             │ Check Available           │
   │           │             │             │             │ (Optimistic Lock)         │
   │           │             │             │             │ [available >= quantity]   │
   │           │             │             │             │             │             │
   │           │             │             │             │ Reserve(quantity)         │
   │           │             │             │             │ [Update: available -= quantity]
   │           │             │             │             │ [version += 1]            │
   │           │             │             │             │             │             │
   │           │             │             │             │ Return: SUCCESS           │
   │           │             │             │<────────────┤             │             │
   │           │             │             │             │             │             │
   │           │             │             │ Update Saga (Step 1 Complete)           │
   │           │             │             │             │             │             │
   │           │             │             │ Step 2: Authorize Payment│              │
   │           │             │             ├─────────────┬────────────>│             │
   │           │             │             │             │             │             │
   │           │             │             │             │             │ Authorize() │
   │           │             │             │             │             ├────────────>│
   │           │             │             │             │             │             │
   │           │             │             │             │             │ [Gateway Processing]
   │           │             │             │             │             │ [~2-3 seconds]
   │           │             │             │             │             │             │
   │           │             │             │             │             │ Return: AUTHORIZED
   │           │             │             │             │             │<────────────┤
   │           │             │             │             │             │             │
   │           │             │             │             │             │ Save Payment│
   │           │             │             │             │             │ (Status=AUTHORIZED)
   │           │             │             │             │             │             │
   │           │             │             │             │ Return: SUCCESS           │
   │           │             │             │<────────────┴─────────────┤             │
   │           │             │             │             │             │             │
   │           │             │             │ Update Saga (Step 2 Complete)           │
   │           │             │             │             │             │             │
   │           │             │             │ Step 3: Confirm Booking   │             │
   │           │             │<────────────┤             │             │             │
   │           │             │             │             │             │             │
   │           │             │ Update Booking (Status=CONFIRMED)       │             │
   │           │             │             │             │             │             │
   │           │             │ Return: SUCCESS           │             │             │
   │           │             ├────────────>│             │             │             │
   │           │             │             │             │             │             │
   │           │             │             │ Update Saga (Step 3 Complete)           │
   │           │             │             │             │             │             │
   │           │             │             │ Step 4: Capture Payment  │              │
   │           │             │             ├─────────────┬────────────>│             │
   │           │             │             │             │             │             │
   │           │             │             │             │             │ Capture()   │
   │           │             │             │             │             ├────────────>│
   │           │             │             │             │             │             │
   │           │             │             │             │             │ Return: CAPTURED
   │           │             │             │             │             │<────────────┤
   │           │             │             │             │             │             │
   │           │             │             │             │             │ Update Payment
   │           │             │             │             │             │ (Status=CAPTURED)
   │           │             │             │             │             │             │
   │           │             │             │             │ Return: SUCCESS           │
   │           │             │             │<────────────┴─────────────┤             │
   │           │             │             │             │             │             │
   │           │             │             │ Update Saga (Status=COMPLETED)          │
   │           │             │             │             │             │             │
   │           │             │             │ Publish: BookingConfirmedEvent          │
   │           │             │             │ [Kafka: booking-events]   │             │
   │           │             │             │             │             │             │
   │           │             │ Return: BookingResponse   │             │             │
   │           │             │ (Status=CONFIRMED)        │             │             │
   │           │<────────────┤             │             │             │             │
   │           │             │             │             │             │             │
   │           │ Cache Response (Idempotency Key, 24h TTL)             │             │
   │           │             │             │             │             │             │
   │ 200 OK    │             │             │             │             │             │
   │ BookingResponse         │             │             │             │             │
   │<──────────┤             │             │             │             │             │
   │           │             │             │             │             │             │
   │           │             │             │             │             │             │
   │           │             │             │  [Async: Notification Service consumes event]
   │           │             │             │             │             │             │
   └───────────┴─────────────┴─────────────┴─────────────┴─────────────┴─────────────┴─────────┘

**Total Duration: ~3-5 seconds**
```

---

### Key Timing Metrics

| Step | Component | Duration | Notes |
|------|-----------|----------|-------|
| 1 | API Gateway Validation | ~5ms | JWT check + rate limit |
| 2 | Booking Creation | ~50ms | DB insert + saga start |
| 3 | Inventory Reservation | ~20ms | Optimistic lock query |
| 4 | Payment Authorization | ~2-3s | External gateway (P99) |
| 5 | Booking Confirmation | ~30ms | DB update |
| 6 | Payment Capture | ~1-2s | External gateway |
| 7 | Response Caching | ~5ms | Redis write |
| **Total** | **End-to-End** | **3-5s** | **P99 target < 5s** |

---

## Diagram 2: Failure Path - Payment Authorization Fails

### Scenario
User creates booking, inventory is reserved, but payment authorization fails. System must compensate by releasing inventory.

### Sequence Diagram

```
┌──────┐  ┌─────────┐  ┌───────── ┐  ┌─────────── ┐  ┌─────────┐  ┌─────────┐  ┌──────────┐
│Client│  │   API   │  │ Booking  │  │Orchestrator│  │Inventory│  │ Payment │  │ External │
│      │  │ Gateway │  │ Service  │  │  Service   │  │ Service │  │ Service │  │ Gateway  │
└──┬───┘  └────┬────┘  └──── ┬────┘  └─────┬───── ┘  └───┬──── ┘  └────┬────┘  └──── ┬────┘
   │           │             │             │             │             │             │
   │ POST /bookings          │             │             │             │             │
   ├──────────>│             │             │             │             │             │
   │           │             │             │             │             │             │
   │           │ CreateBooking()           │             │             │             │
   │           ├────────────>│             │             │             │             │
   │           │             │             │             │             │             │
   │           │             │ Save Booking (PENDING)    │             │             │
   │           │             │             │             │             │             │
   │           │             │ StartSaga() │             │             │             │
   │           │             ├────────────>│             │             │             │
   │           │             │             │             │             │             │
   │           │             │             │ Reserve Inventory         │             │
   │           │             │             ├────────────>│             │             │
   │           │             │             │             │             │             │
   │           │             │             │             │ SUCCESS     │             │
   │           │             │             │<────────────┤             │             │
   │           │             │             │             │             │             │
   │           │             │             │ Authorize Payment         │             │
   │           │             │             ├─────────────┬────────────>│             │
   │           │             │             │             │             │             │
   │           │             │             │             │             │ Authorize() │
   │           │             │             │             │             ├────────────>│
   │           │             │             │             │             │             │
   │           │             │             │             │             │  DECLINED 
   │           │             │             │             │             │ (Insufficient Funds)
   │           │             │             │             │             │<────────────┤
   │           │             │             │             │             │             │
   │           │             │             │             │ Return: FAILED            │
   │           │             │             │<────────────┴─────────────┤             │
   │           │             │             │             │             │             │
   │           │             │             │  PAYMENT FAILED           |             |
   │           │             │             │ Start Compensation        │             │
   │           │             │             │             │             │             │
   │           │             │             │ Compensate: Release Inventory           │
   │           │             │             ├────────────>│             │             │
   │           │             │             │             │             │             │
   │           │             │             │             │ Release(quantity)         │
   │           │             │             │             │ [Update: available += quantity]
   │           │             │             │             │ [version += 1]            │
   │           │             │             │             │             │             │
   │           │             │             │             │ Return: SUCCESS           │
   │           │             │             │<────────────┤             │             │
   │           │             │             │             │             │             │
   │           │             │             │ Update Saga (Status=COMPENSATED)        │
   │           │             │             │             │             │             │
   │           │             │             │ Cancel Booking            │             │
   │           │             │<────────────┤             │             │             │
   │           │             │             │             │             │             │
   │           │             │ Update Booking (Status=CANCELLED)       │             │
   │           │             │ [Reason: Payment Failed]  │             │             │
   │           │             │             │             │             │             │
   │           │             │ Return: SUCCESS           │             │             │
   │           │             ├────────────>│             │             │             │
   │           │             │             │             │             │             │
   │           │             │             │ Publish: BookingCancelledEvent          │
   │           │             │             │ [Kafka: booking-events]   │             │
   │           │             │             │             │             │             │
   │           │             │ Return: BookingResponse   │             │             │
   │           │             │ (Status=CANCELLED)        │             │             │
   │           │             │ (Reason: Payment Failed)  │             │             │
   │           │<────────────┤             │             │             │             │
   │           │             │             │             │             │             │
   │ 422 Unprocessable Entity              │             │             │             │
   │ { "error": "Payment declined" }       │             │             │             │
   │<──────────┤             │             │             │             │             │
   │           │             │             │             │             │             │
   │           │             │             │  [Async: Notification sends cancellation email]
   │           │             │             │             │             │             │
   └───────────┴─────────────┴─────────────┴─────────────┴─────────────┴─────────────┴─────────┘

**Total Duration: ~3-4 seconds (similar to happy path, compensation is fast)**
```

---

### Compensation Guarantees

1. **Idempotent Compensation**: Releasing inventory twice is safe (no-op if already released)
2. **Retry on Failure**: If compensation fails, retry up to 5 times with exponential backoff
3. **Manual Intervention**: After 5 retries, send alert to ops team for manual resolution
4. **Audit Trail**: All compensation attempts logged for debugging

---

## Diagram 3: Retry with Idempotency Key

### Scenario
Network glitch causes client to not receive initial response. Client retries with same idempotency key. System returns cached response without reprocessing.

### Sequence Diagram

```
┌──────┐  ┌─────────┐  ┌─────────┐  ┌───────┐
│Client│  │   API   │  │ Booking │  │ Redis │
│      │  │ Gateway │  │ Service │  │ Cache │
└──┬───┘  └────┬────┘  └──── ┬───┘  └─── ┬──┘
   │           │             │           │
   │ POST /bookings          │           │
   │ Idempotency-Key: abc123 │           │
   ├──────────>│             │           │
   │           │             │           │
   │           │ CreateBooking()         │
   │           ├────────────>│           │
   │           │             │           │
   │           │             │ Check Cache(abc123)
   │           │             ├──────────>│
   │           │             │           │
   │           │             │ MISS      │
   │           │             │<──────────┤
   │           │             │           │
   │           │             │ [Process booking...]
   │           │             │ [Takes 3 seconds] │
   │           │             │           │
   │           │             │ Save Response     │
   │           │             ├──────────>│
   │           │             │ (TTL=24h) │
   │           │             │           │
   │           │             │ OK        │
   │           │             │<──────────┤
   │           │             │           │
   │ 200 OK    │             │           │
   │ BookingResponse         │           │
   │<──────────┤             │           │
   │     │     │             │           │
   │     │   Network Timeout           
   │     │  (Client doesn't receive response)
   │     │     │             │           │
   │     ▼     │             │           │
   │  [Client retries after 2 seconds]   │
   │           │             │           │
   │ POST /bookings                      │
   │ Idempotency-Key: abc123  (SAME KEY) │
   ├──────────>│             │           │
   │           │             │           │
   │           │ CreateBooking()         │
   │           ├────────────>│           │
   │           │             │           │
   │           │             │ Check Cache(abc123)
   │           │             ├──────────>│
   │           │             │           │
   │           │             │ HIT       │
   │           │             │ Return cached response
   │           │             │<──────────┤
   │           │             │           │
   │           │             │ [No DB access]
   │           │             │ [No Saga execution]
   │           │             │ [< 10ms total]    
   │           │             │           │
   │ 200 OK    │             │           │
   │ BookingResponse         │           │
   │ (Same as first response)            │
   │<──────────┤             │           │
   │           │             │           │
   │  Success  |             |           |
   │           │             │           │
   └───────────┴─────────────┴───────────┴───────┘

**Retry Response Time: < 10ms (cached)**
**No duplicate booking created**
**No duplicate payment**
```

---

### Idempotency Implementation Notes

1. **Key Generation**: Client generates UUID v4 (cryptographically random)
2. **Header**: `Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000`
3. **Cache Storage**: Redis with 24-hour TTL
4. **Cache Key Format**: `idempotency:{service}:{key}` → `idempotency:booking:abc123`
5. **Response Caching**: Entire response body + status code
6. **Fallback**: Database unique constraint on `(userId, idempotencyKey)` as last resort

---

## Diagram 4: Booking Expiration Timeout

### Scenario
User creates booking but doesn't complete payment within 5 minutes. System auto-cancels and releases inventory.

### Sequence Diagram

```
┌──────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐
│Client│  │ Booking │  │Orchestrator  │Inventory│
│      │  │ Service │  │  Service     │ Service │
└──┬───┘  └────┬────┘  └─────┬─────┘  └────┬────┘
   │           │             │             │
   │ POST /bookings          │             │
   ├──────────>│             │             │
   │           │             │             │
   │           │ Create Booking            │
   │           │ (Status=PENDING)          │
   │           │ (Timeout=5min)            │
   │           │             │             │
   │           │ Reserve Inventory         │
   │           ├─────────────┬────────────>│
   │           │             │             │
   │ 200 OK    │             │             │
   │ (Booking created)       │             │
   │<──────────┤             │             │
   │           │             │             │
   │ [User abandons checkout page]         │
   │           │             │             │
   │           │    [5 minutes pass...]  
   │           │             │             │
   │           │  [Scheduled Job: Check Expired Bookings]
   │           │             │             │
   │           │  ← Cron Job (Every 1 min) │
   │           │             │             │
   │           │ Find Expired Bookings     │
   │           │ WHERE status='PENDING'    │
   │           │ AND created_at < NOW() - 5min
   │           │             │             │
   │           │ [Booking found!]          │
   │           │             │             │
   │           │ CancelExpiredBooking()    │
   │           ├────────────>│             │
   │           │             │             │
   │           │             │ Release Inventory
   │           │             ├────────────>│
   │           │             │             │
   │           │             │  Restore(quantity)
   │           │             │             │
   │           │             │ SUCCESS     │
   │           │             │<────────────┤
   │           │             │             │
   │           │ Update Booking            │
   │           │<────────────┤             │
   │           │             │             │
   │           │ Set Status=CANCELLED      │
   │           │ Set Reason=TIMEOUT        │
   │           │             │             │
   │           │ Publish: BookingCancelledEvent
   │           │ [Kafka: booking-events]   │
   │           │             │             │
   └───────────┴─────────────┴─────────────┴─────────┘

**Expiration Check Frequency: Every 1 minute**
**Max Delay: 1 minute (time until next cron run)**
```

---

### Timeout Configuration

| Environment | Timeout Duration | Check Frequency |
|-------------|-----------------|-----------------|
| Production | 5 minutes | Every 1 minute |
| Staging | 2 minutes | Every 30 seconds |
| Development | 1 minute | Every 30 seconds |

---

## Diagram 5: Saga Recovery After Service Crash

### Scenario
Orchestration Service crashes mid-saga. On restart, it detects incomplete sagas and resumes or compensates.

### Sequence Diagram

```
┌───────────┐  ┌─────────┐  ┌─────────┐
│Orchestrator  │Inventory│  │ Payment │
│  Service     │ Service │  │ Service │
└─────┬─────┘  └────┬────┘  └──── ┬───┘
      │             │             │
      │ Saga Started│             │
      │             │             │
      │ Step 1: Reserve Inventory │
      ├────────────>│             │
      │             │             │
      │ SUCCESS     │             │
      │<────────────┤             │
      │             │             │
      │ [Update DB: Step 1 Complete]
      │             │             │
      │ Step 2: Authorize Payment │
      ├─────────────┬────────────>│
      │             │             │
      │             │   CRASH!    │
      │             │ [OOM Kill / Pod Restart]
      ▼             │             │
   [Down]           │             │
      │             │             │
      │  [Kubernetes restarts pod in 10s]
      │             │             │
      ▲             │             │
      │ [Service Starting...]     │
      │             │             │
      │ OnStartup: Recovery()     │
      │             │             │
      │ SELECT * FROM saga_instances
      │ WHERE status IN ('STARTED', 'COMPENSATING')
      │ AND last_updated < NOW() - 30s
      │             │             │
      │ [Found incomplete saga!]  │
      │             │             │
      │ Resume Saga: Check last completed step
      │ [Step 1: Complete]        │
      │ [Step 2: Not Started]     │
      │             │             │
      │ Retry Step 2: Authorize Payment
      ├─────────────┬────────────>│
      │             │             │
      │             │ SUCCESS     │
      │<────────────┴─────────────┤
      │             │             │
      │ [Continue saga...]        │
      │             │             │
      └─────────────┴─────────────┴─────────┘

**Recovery Time: < 30 seconds**
**No data loss (saga state persisted)**
```

---

### Recovery Guarantees

1. **Crash Detection**: Saga not updated for > 30 seconds = assumed crashed
2. **State Persistence**: Every saga step completion saved to database
3. **Idempotent Steps**: All saga steps can be safely retried
4. **Timeout Handling**: If step stuck > 2 minutes, initiate compensation
5. **Manual Intervention**: After 5 recovery attempts, alert ops team

---

## Performance Summary

| Workflow | P50 Latency | P99 Latency | Success Rate | Notes |
|----------|------------|-------------|--------------|-------|
| Happy Path Booking | 3.2s | 4.8s | 99.5% | Includes payment gateway |
| Cached Retry | 5ms | 15ms | 100% | Idempotency hit |
| Payment Failure Compensation | 3.5s | 5.2s | 99.8% | Auto-rollback |
| Booking Expiration | N/A | 60s | 100% | Max delay = cron interval |
| Saga Recovery | N/A | 30s | 99.9% | Post-crash resume |

---

## Related Documents

- [High-Level Architecture](../high-level/) - System overview
- [Data Flow](../data-flow/) - Data propagation patterns
- [Failure Scenarios](../../docs/failure-scenarios.md) - Production failure handling
- [Trade-offs](../../docs/trade-offs.md) - Design decisions rationale
