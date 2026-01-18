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
- **Docker 24+** and Docker Compose 2+
- **Java 17+** (OpenJDK or Oracle JDK)
- **Maven 3.8+**
- **8GB RAM minimum** (16GB recommended)

### Local Development Setup

#### 1. Start Infrastructure Services

```bash
# Start PostgreSQL, Kafka, Redis, Prometheus, Grafana, and Jaeger
docker-compose up -d

# Wait for all services to be healthy (approximately 30-60 seconds)
docker-compose ps

# Verify infrastructure health
docker-compose logs postgres-booking | tail -20
docker-compose logs kafka-1 | tail -20
docker-compose logs redis-master-1 | tail -20
```

#### 2. Build the Project

```bash
# Build all modules
./mvnw clean install

# Skip tests for faster build (not recommended)
./mvnw clean install -DskipTests

# Build specific module
cd booking-service
../mvnw clean package
```

#### 3. Run Services Locally

Option A: Run all services using Maven (for development)
```bash
# Terminal 1: API Gateway (Port 8000)
cd api-gateway
../mvnw spring-boot:run

# Terminal 2: Booking Service (Port 8001)
cd booking-service
../mvnw spring-boot:run

# Terminal 3: Inventory Service (Port 8002)
cd inventory-service
../mvnw spring-boot:run

# Terminal 4: Payment Service (Port 8003)
cd payment-service
../mvnw spring-boot:run

# Terminal 5: Orchestration Service (Port 8004)
cd orchestration-service
../mvnw spring-boot:run

# Terminal 6: Notification Service (Port 8005)
cd notification-service
../mvnw spring-boot:run
```

Option B: Build and run Docker containers
```bash
# Build Docker images for all services
./mvnw clean package jib:dockerBuild

# Start all services with docker-compose
docker-compose -f docker-compose.yml -f docker-compose.services.yml up -d
```

#### 4. Verify Services

```bash
# Check API Gateway health
curl http://localhost:8000/actuator/health

# Check Booking Service health
curl http://localhost:8001/actuator/health

# View all service metrics endpoints
curl http://localhost:8001/actuator/prometheus
```

#### 5. Access Monitoring Dashboards

- **Kafka UI**: http://localhost:8080 - Monitor Kafka topics and messages
- **Prometheus**: http://localhost:9090 - Query metrics
- **Grafana**: http://localhost:3000 (admin/admin) - Visualize metrics
- **Jaeger**: http://localhost:16686 - Distributed tracing
- **API Documentation (Swagger)**: 
  - Booking Service: http://localhost:8001/swagger-ui.html
  - Inventory Service: http://localhost:8002/swagger-ui.html
  - Payment Service: http://localhost:8003/swagger-ui.html

### Create Kafka Topics

```bash
# Access Kafka container
docker exec -it kafka-1 bash

# Create topics with replication factor 3
kafka-topics --create --topic booking-events \
  --bootstrap-server kafka-1:9092,kafka-2:9093,kafka-3:9094 \
  --partitions 12 --replication-factor 3

kafka-topics --create --topic payment-events \
  --bootstrap-server kafka-1:9092,kafka-2:9093,kafka-3:9094 \
  --partitions 12 --replication-factor 3

kafka-topics --create --topic inventory-events \
  --bootstrap-server kafka-1:9092,kafka-2:9093,kafka-3:9094 \
  --partitions 12 --replication-factor 3

kafka-topics --create --topic notification-queue \
  --bootstrap-server kafka-1:9092,kafka-2:9093,kafka-3:9094 \
  --partitions 12 --replication-factor 3

# List all topics
kafka-topics --list --bootstrap-server kafka-1:9092
```

### Stop Services

```bash
# Stop all Docker services
docker-compose down

# Stop and remove volumes (clean slate)
docker-compose down -v

# Stop specific service
docker-compose stop booking-service
```

---

## Project Structure

```
high-throughput-booking-orchestration-system/
├── pom.xml                          # Parent POM with dependency management
├── docker-compose.yml               # Infrastructure services
├── .gitignore                       # Git ignore rules
├── README.md                        # This file
│
├── common/                          # Shared utilities and DTOs
│   ├── src/main/java/              # Common code
│   └── pom.xml
│
├── api-gateway/                     # Spring Cloud Gateway
│   ├── src/main/java/              # Gateway implementation
│   ├── src/main/resources/         # Configuration
│   └── pom.xml
│
├── booking-service/                 # Booking management
│   ├── src/main/java/              # Service implementation
│   ├── src/main/resources/         # Configuration
│   │   └── db/migration/           # Flyway migrations
│   ├── src/test/java/              # Tests
│   └── pom.xml
│
├── inventory-service/               # Inventory management
│   ├── src/main/java/
│   ├── src/main/resources/
│   │   └── db/migration/
│   ├── src/test/java/
│   └── pom.xml
│
├── payment-service/                 # Payment processing
│   ├── src/main/java/
│   ├── src/main/resources/
│   │   └── db/migration/
│   ├── src/test/java/
│   └── pom.xml
│
├── orchestration-service/           # Saga orchestration
│   ├── src/main/java/
│   ├── src/main/resources/
│   │   └── db/migration/
│   ├── src/test/java/
│   └── pom.xml
│
├── notification-service/            # Notifications (email/SMS)
│   ├── src/main/java/
│   ├── src/main/resources/
│   │   └── db/migration/
│   ├── src/test/java/
│   └── pom.xml
│
├── observability/                   # Monitoring configuration
│   ├── prometheus/
│   │   └── prometheus.yml          # Prometheus config
│   └── grafana/
│       └── provisioning/           # Grafana datasources and dashboards
│
├── docs/                           # Documentation
│   ├── problem-statement.md
│   ├── assumptions.md
│   ├── trade-offs.md
│   ├── failure-scenarios.md
│   └── future-improvements.md
│
├── architecture/                   # Architecture documentation
│   ├── high-level.md
│   ├── sequence-diagrams.md
│   └── data-flow.md
│
└── planning/                       # Project planning
    └── issues.csv                  # Task breakdown
```

---

## Build Tools and Code Quality

### Maven Plugins Configured

#### Code Formatting (Spotless)
Enforces Google Java Format style automatically:

```bash
# Check code format
./mvnw spotless:check

# Apply code format
./mvnw spotless:apply
```

#### Static Analysis (SpotBugs)
Detects potential bugs and security vulnerabilities:

```bash
# Run SpotBugs analysis
./mvnw spotbugs:check

# Generate HTML report
./mvnw spotbugs:spotbugs
# Report: target/spotbugs.html
```

Includes **FindSecBugs** plugin for security analysis.

#### Test Coverage (JaCoCo)
Enforces 80% line coverage and 75% branch coverage:

```bash
# Run tests with coverage
./mvnw clean test

# Generate coverage report
./mvnw jacoco:report
# Report: target/site/jacoco/index.html

# Check coverage thresholds
./mvnw jacoco:check
```

#### Build with All Quality Checks

```bash
# Build with all checks (production profile)
./mvnw clean verify -Pprod

# This runs:
# - Unit tests
# - Integration tests
# - Code formatting check
# - SpotBugs analysis
# - JaCoCo coverage check
```

### Maven Commands Reference

```bash
# Clean build
./mvnw clean

# Compile
./mvnw compile

# Run unit tests
./mvnw test

# Run integration tests
./mvnw verify

# Package JAR
./mvnw package

# Install to local repository
./mvnw install

# Skip tests
./mvnw install -DskipTests

# Update dependencies
./mvnw versions:display-dependency-updates

# Show dependency tree
./mvnw dependency:tree

# Build Docker images with Jib
./mvnw compile jib:dockerBuild
```

---

## Technology Stack Details

### Backend Framework
- **Java 17** - Modern JDK with records, sealed classes, pattern matching
- **Spring Boot 3.2.2** - Latest stable release with native support
- **Spring Cloud 2023.0.0** - Gateway, Config, Netflix components

### Databases
- **PostgreSQL 16** - Primary transactional database (5 instances - one per service)
  - Connection pooling: HikariCP
  - Migrations: Flyway
  - Features: JSONB support, partial indexes, CTEs
  
### Messaging
- **Apache Kafka 3.6.1** - Event streaming platform
  - 3 brokers cluster
  - ZooKeeper coordination
  - Schema Registry for Avro schemas
  - 12 partitions per topic, replication factor 3
  - 7-day retention policy

### Caching
- **Redis 7.2** - Distributed cache and session store
  - 3 master nodes (future: + 3 replicas)
  - AOF + RDB persistence
  - LRU eviction policy

### Resilience Libraries
- **Resilience4j 2.2.0** - Circuit breakers, retries, bulkheads
- **Exponential backoff** with jitter for retries
- **Timeout handling** for all external calls

### Observability Stack
- **Micrometer 1.12.2** - Metrics facade
- **Prometheus 2.49.1** - Metrics collection and storage
- **Grafana 10.3.1** - Visualization and alerting
- **OpenTelemetry 1.34.1** - Distributed tracing
- **Jaeger 1.53** - Trace backend
- **Logback + Logstash Encoder 7.4** - Structured JSON logging

### API Documentation
- **Springdoc OpenAPI 3** - OpenAPI 3.0 spec generation
- **Swagger UI** - Interactive API documentation

### Testing Tools
- **JUnit 5.10.1** - Unit testing framework
- **Mockito 5.8.0** - Mocking framework
- **Testcontainers 1.19.3** - Integration testing with real databases
- **REST Assured 5.4.0** - API testing
- **WireMock 3.3.1** - External service mocking

### Build and Deployment
- **Maven 3.8+** - Build tool
- **Jib 3.4.0** - Containerless Docker builds
- **Docker & Docker Compose** - Local development environment
- **Kubernetes-ready** - Health probes, resource limits, graceful shutdown

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