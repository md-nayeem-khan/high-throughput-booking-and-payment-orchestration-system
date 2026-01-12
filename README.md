# High-Throughput Booking and Payment Orchestration System

> A production-grade, distributed booking and payment orchestration platform designed to handle concurrent reservations, idempotent payment workflows, and distributed consistency across multiple microservices. Built with fault tolerance, horizontal scalability, and real-world production patterns in mind.

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue.svg)](https://www.postgresql.org/)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.6-black.svg)](https://kafka.apache.org/)
[![Redis](https://img.shields.io/badge/Redis-7.2-red.svg)](https://redis.io/)

---

## Project Overview

This system addresses the complex challenges faced by high-scale booking platforms (travel, ride-hailing, event ticketing) where **inventory consistency**, **payment reliability**, and **concurrent access** are critical. Inspired by real-world systems at companies like Agoda, Grab, and Airbnb, this implementation demonstrates production-ready patterns for handling:

- **Atomic booking and payment workflows** across distributed services
- **Inventory over-booking prevention** under high concurrency
- **Payment idempotency** to handle retries safely
- **Distributed transaction orchestration** with compensating actions
- **Fault tolerance** and graceful degradation under partial failures

**Business Context:** In booking systems, a single oversold item or duplicate payment can result in significant financial loss and customer trust erosion. This architecture ensures exactly-once semantics for payments while maintaining high availability and throughput.

---

## Core Capabilities

### Transactional Integrity
- ✅ **Saga Pattern Implementation** for distributed transactions with compensating actions
- ✅ **Optimistic Locking** to prevent inventory overselling without distributed locks
- ✅ **Idempotency Keys** for safe retry of payment and booking operations
- ✅ **Two-Phase Booking** (reserve → confirm) with automatic timeout handling

### Scalability & Performance
- ✅ **Horizontal Scalability** across all service layers
- ✅ **Redis-based Distributed Caching** for inventory hot paths
- ✅ **Asynchronous Event-Driven Communication** via Kafka
- ✅ **Database Read Replicas** for query scalability
- ✅ **Connection Pooling** and optimized database access patterns

### Reliability & Resilience
- ✅ **Circuit Breakers** for external payment gateway failures (Resilience4j)
- ✅ **Exponential Backoff Retry** with jitter for transient failures
- ✅ **Dead Letter Queues** for failed message processing
- ✅ **Health Checks** and graceful shutdown mechanisms
- ✅ **Distributed Tracing** for end-to-end request visibility

### Observability
- ✅ **Structured Logging** with correlation IDs across services
- ✅ **Prometheus Metrics** (latency, throughput, error rates)
- ✅ **Grafana Dashboards** for real-time monitoring
- ✅ **OpenTelemetry Integration** for distributed tracing
- ✅ **Custom Business Metrics** (booking success rate, payment conversion)

---

## Tech Stack

### Backend Services
- **Java 17** with modern language features (records, sealed classes)
- **Spring Boot 3.2** (Web, Data JPA, Cloud)
- **Spring Cloud Gateway** for API gateway and rate limiting
- **Spring Cloud Config** for centralized configuration

### Data Layer
- **PostgreSQL 15** (primary transactional database)
- **Redis 7.2** (distributed caching, rate limiting, session management)
- **Flyway** for database migrations and versioning

### Messaging & Events
- **Apache Kafka 3.6** (event streaming, async communication)
- **Spring Kafka** for producer/consumer implementation
- **Avro** for schema evolution and backward compatibility

### Resilience & Patterns
- **Resilience4j** (circuit breakers, rate limiters, bulkheads)
- **Saga Pattern** for distributed transaction orchestration
- **Outbox Pattern** for reliable event publishing

### Observability & Operations
- **Prometheus** (metrics collection)
- **Grafana** (visualization and alerting)
- **OpenTelemetry** (distributed tracing)
- **ELK Stack** (centralized logging)
- **Spring Boot Actuator** (health checks and endpoints)

### Containerization & Orchestration
- **Docker** & **Docker Compose** for local development
- **Kubernetes-ready** with health probes and resource limits
- **Helm Charts** (optional for K8s deployment)

---

## Architecture Summary

The system follows a **microservice-based architecture** with clear service boundaries and data ownership. Communication patterns include:

- **Synchronous REST APIs** for real-time operations (booking creation, payment initiation)
- **Asynchronous Kafka Events** for eventual consistency and cross-service notifications
- **API Gateway** for routing, authentication, and rate limiting

**Service Decomposition:**
- **Booking Service**: Manages booking lifecycle (create, confirm, cancel)
- **Inventory Service**: Maintains available capacity with optimistic locking
- **Payment Service**: Handles payment processing with idempotency
- **Orchestration Service**: Coordinates Saga workflows with compensation logic
- **Notification Service**: Sends confirmations and alerts to users

**Distributed Consistency Strategy:**  
The Saga pattern ensures that multi-service transactions either complete fully or compensate gracefully. Each service publishes domain events to Kafka, enabling eventual consistency across bounded contexts. The Outbox pattern guarantees reliable event publishing even during database failures.

**Concurrency Control:**  
Optimistic locking with version numbers prevents lost updates. Redis distributed locks are used sparingly for critical sections requiring strong coordination (e.g., flash sale inventory).

**Detailed Architecture**: See [architecture/](architecture/) folder for diagrams and deep dives.

---

## Reliability & Scalability Highlights

### Idempotency & Retries
- **Idempotency Keys**: All state-changing APIs accept client-generated idempotency keys stored in Redis (24-hour TTL)
- **Retry Strategy**: Exponential backoff with max 5 retries for transient failures
- **Duplicate Detection**: Database unique constraints on booking IDs and payment transaction IDs

### Circuit Breakers & Timeouts
- **Payment Gateway Circuit Breaker**: Opens after 50% failure rate over 10 requests
- **Timeout Configuration**: 
  - Booking API: 3s timeout
  - Payment API: 5s timeout
  - Inter-service calls: 2s timeout

### Horizontal Scalability
- **Stateless Services**: All services can scale independently
- **Kafka Consumer Groups**: Parallel processing with automatic rebalancing
- **Database Connection Pooling**: HikariCP with 20 connections per instance

### Transaction Boundaries
- **Single Database per Service**: No distributed transactions across databases
- **Saga Coordination**: Orchestration service maintains saga state in dedicated tables
- **Compensation Logic**: Each service implements rollback operations for its local transactions

### Data Consistency Models
- **Strong Consistency**: Within single service boundaries (ACID transactions)
- **Eventual Consistency**: Across services via Kafka events
- **Read-Your-Writes**: Achieved through routing consistency in API Gateway

---

## Quick Start

### Prerequisites
- Docker 24+ and Docker Compose 2+
- Java 17+ (for local development)
- 8GB RAM minimum

### Run Locally

```bash
# Clone the repository
git clone https://github.com//md-nayeem-khan/high-throughput-booking-and-payment-orchestration-system.git
cd high-throughput-booking-and-payment-orchestration-system

# Start all services (PostgreSQL, Kafka, Redis, all microservices)
docker-compose up -d

# Check service health
curl http://localhost:8080/actuator/health

# View logs
docker-compose logs -f booking-service
```

---

## Documentation Index

Comprehensive design documentation is available in the [`docs/`](docs/) folder:

| Document | Description |
|----------|-------------|
| [Problem Statement](docs/problem-statement.md) | Business context, functional and non-functional requirements |
| [Assumptions](docs/assumptions.md) | System boundaries, business rules, and out-of-scope items |
| [Trade-offs](docs/trade-offs.md) | Key architectural decisions with alternatives and rationale |
| [Failure Scenarios](docs/failure-scenarios.md) | Production failure cases and recovery strategies |
| [Future Improvements](docs/future-improvements.md) | Scalability enhancements and roadmap |

**Architecture Diagrams:**
- [High-Level Architecture](architecture/high-level/)
- [Sequence Diagrams](architecture/sequence-diagrams/)
- [Data Flow](architecture/data-flow/)

---

## Testing Strategy

- **Unit Tests**: xxx%+ coverage with JUnit 5 and Mockito
- **Integration Tests**: Testcontainers for PostgreSQL, Kafka, Redis
- **Contract Tests**: Spring Cloud Contract for API compatibility
- **Load Tests**: Gatling scenarios for xxxk concurrent bookings
- **Chaos Engineering**: Resilience testing with Toxiproxy

---

## Development

### Build from Source

```bash
# Build all services
./mvnw clean package

# Run specific service
cd booking-service
../mvnw spring-boot:run

# Run tests
./mvnw test
```

### Code Quality

```bash
# Static analysis
./mvnw spotbugs:check

# Code formatting
./mvnw spring-javaformat:apply

# Dependency check
./mvnw versions:display-dependency-updates
```

---

## Performance Benchmarks

Tested on: xxx-core CPU, xxxGB RAM, SSD storage

| Metric | Value |
|--------|-------|
| Concurrent Bookings | xxx req/s |
| P50 Latency | xxxms |
| P99 Latency | xxxms |
| P99.9 Latency | xxxms |
| Successful Booking Rate | xxx% |
| Payment Idempotency Success | xxx% |
| Database Connection Pool Utilization | xxx% |

---

## Contributing

This is a portfolio project, but feedback and suggestions are welcome! Please open an issue for discussions.

---

## License

MIT License - See [LICENSE](LICENSE) file for details.

---

## Author

**Md. Nayeem Khan**  
Senior Software Engineer

- LinkedIn: [linkedin.com/in/mdnayeemkhan/](https://linkedin.com/in/mdnayeemkhan/)
- Email: nayeem1505@gmail.com
- Portfolio: [nayeemkhan.dev](https://www.nayeemkhan.dev/)

---