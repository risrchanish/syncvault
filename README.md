# SyncVault

### Production-grade distributed file sync platform with AI document intelligence

![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-7.6-231F20?style=flat-square&logo=apachekafka&logoColor=white)
![pgvector](https://img.shields.io/badge/pgvector-PostgreSQL_15-336791?style=flat-square&logo=postgresql&logoColor=white)
![OpenAI](https://img.shields.io/badge/OpenAI-GPT--4o--mini-412991?style=flat-square&logo=openai&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat-square&logo=docker&logoColor=white)
![Status](https://img.shields.io/badge/Phase_1-Complete-brightgreen?style=flat-square)

---

## Overview

SyncVault is a **Dropbox-like distributed file synchronisation platform** built as a portfolio project to demonstrate production-level distributed systems design, event-driven architecture, and AI integration in a single cohesive system.

The project deliberately avoids frameworks and shortcuts where they would obscure engineering decisions — the AI SDK is hand-rolled, JWT validation is implemented from scratch, and every design decision is traceable to a concrete trade-off.

**Phase 1 is complete and fully functional.** All five services start with a single `docker compose up -d` command, pass 289 automated test cases, and enforce an 80% JaCoCo coverage gate on every build.

---

## What Is Built — Phase 1

### Services at a Glance

| Service | Port | Database | Purpose |
|---|---|---|---|
| api-gateway | 8080 | — | JWT validation, rate limiting, routing |
| user-service | 8081 | PostgreSQL 5432 | Auth, registration, token lifecycle |
| file-service | 8082 | PostgreSQL 5433 + pgvector | Upload, versioning, AI pipeline, search |
| notification-service | 8083 | PostgreSQL 5434 | Kafka consumer, email dispatch |
| ai-sdk | — | — | Custom LLM client library (internal) |

---

### 1 — User Service (port 8081)

Handles the complete user identity lifecycle.

- **Registration** with email uniqueness enforcement, BCrypt password hashing
- **Login** returns a short-lived access token (15 min) and a long-lived refresh token (7 days)
- **Token rotation** — refresh tokens are single-use; each refresh issues a new pair and revokes the old one
- **Logout** server-side revokes the refresh token; stored as a SHA-256 hash so a database breach is useless without the original UUID
- Spring Security with a stateless JWT filter chain; no session state
- Flyway-managed schema with UUID primary keys (prevents enumeration attacks)

### 2 — File Service (port 8082)

The core of the platform. Handles the complete file lifecycle including AI enrichment.

**Storage & Versioning**
- Upload files to AWS S3 (LocalStack in development) with PostgreSQL metadata
- Every upload creates a `FileVersion` snapshot — full version history preserved
- Optimistic locking for conflict detection: concurrent edits to the same base version are caught and returned as a `409 CONFLICT` response with a conflict copy naming strategy (`filename_conflict_<timestamp>.ext`)
- Soft delete with a **30-day recovery window** before permanent removal

**AI Document Intelligence Pipeline**

Each upload triggers two independent virtual threads:

```
Upload
  ├── Thread 1: ai-summary-{id}
  │     ├── Apache Tika  → extract plain text from PDF/DOCX/TXT
  │     ├── OpenAI GPT-4o-mini → generate natural language summary
  │     └── OpenAI GPT-4o-mini → generate structured description + tags (JSON)
  │
  └── Thread 2: ai-embed-{id}
        ├── Apache Tika  → extract plain text
        ├── OpenAI text-embedding-3-small → 1536-dimension vector
        └── pgvector (JdbcTemplate + ::vector cast) → store for cosine similarity search
```

**Semantic Search**
- Query text is embedded at search time using the same model
- pgvector `<=>` cosine distance operator ranks results
- Validated: **3.4× relevance discrimination** — relevant documents score 0.61 cosine similarity vs 0.18 for unrelated documents

### 3 — API Gateway (port 8080)

Single entry point for all client traffic.

- **JWT validation filter** — validates every non-public request before it reaches a downstream service; extracts `userId` and forwards it as `X-User-Id` header
- **Fixed-window rate limiter** — 100 requests/min for authenticated users, 10 requests/min for unauthenticated; keyed by user ID or client IP
- Spring Cloud Gateway with route definitions for all downstream services
- Public paths: `/auth/**`, `/actuator/**`, Swagger UI

### 4 — Notification Service (port 8083)

Event-driven email notifications via Kafka.

- Consumes `file-events` topic messages published by file-service
- Handles `FILE_UPLOADED`, `FILE_UPDATED`, `FILE_DELETED`, `FILE_CONFLICT` event types
- **Exactly-once delivery** enforced via a `ProcessedEvent` table — duplicate Kafka messages are silently dropped
- Gmail SMTP integration with configurable dev-recipient override for routing all emails to a single inbox during development
- Gracefully degrades if Gmail credentials are absent (no startup failure)

### 5 — AI SDK (custom Java library)

A standalone, independently testable Java library (`com.syncvault:ai-sdk`) that abstracts all LLM interactions.

**Core Abstractions**
```
LLMClient (interface)
  ├── complete(prompt)
  ├── completeWithSystem(system, prompt)
  ├── embed(text)  → float[]
  ├── countTokens(text)
  └── isWithinLimit(text)

Implementations:
  ├── OpenAIClient     — chat completion + embeddings (text-embedding-3-small)
  ├── ClaudeClient     — chat completion via Anthropic Messages API
  └── ResilientLLMClient — decorator adding CB + retry on top of any provider
```

**Resilience**
- `ResilientLLMClient` wraps any `LLMClient` with Resilience4j
- **Circuit Breaker** (outer) — opens after threshold failures, prevents thundering herd against the LLM API
- **Retry** (inner) — retries on 429 (rate limit) and 5xx errors; does not retry on 401 (auth failure) or 400 (bad request)
- Token truncation with configurable soft/hard thresholds to prevent `TokenLimitExceededException` before the request is sent

**Token Management**
- JTokkit tokeniser (cl100k_base encoding) for accurate pre-flight token counting
- `truncateToLimit` and `truncateToPercent` utilities for safe context window management

**Test Coverage**
- 80 unit tests with full mock HTTP server validation of request/response shapes
- Verified against real Anthropic and OpenAI response formats

---

## Architecture

```
                          ┌─────────────────────────────────────────────────────┐
                          │                    Client                           │
                          └─────────────────────┬───────────────────────────────┘
                                                 │ HTTP
                          ┌──────────────────────▼──────────────────────────────┐
                          │              API Gateway  :8080                     │
                          │   JWT Validation Filter → Rate Limiter → Router     │
                          └───────┬──────────────┬──────────────┬───────────────┘
                                  │              │              │
               ┌──────────────────▼──┐    ┌──────▼──────┐  ┌───▼──────────────┐
               │  User Service :8081 │    │ File Service│  │Notification Svc  │
               │                     │    │    :8082    │  │     :8083        │
               │  Auth / JWT Issuer  │    │             │  │                  │
               │  BCrypt + Flyway    │    │ Upload      │  │ Kafka Consumer   │
               │                     │    │ Versioning  │  │ Gmail SMTP       │
               └──────────┬──────────┘    │ Conflict    │  └────────┬─────────┘
                          │               │ Soft Delete │           │
               ┌──────────▼──────────┐    │ AI Pipeline │  ┌────────▼─────────┐
               │  PostgreSQL :5432   │    │ Sem. Search │  │ PostgreSQL :5434  │
               │  users_db           │    └──────┬──────┘  │ notifications_db  │
               └─────────────────────┘           │         └───────────────────┘
                                                 │
                              ┌──────────────────┼────────────────────┐
                              │                  │                    │
                   ┌──────────▼──────┐  ┌────────▼────────┐  ┌───────▼──────────┐
                   │  PostgreSQL     │  │   LocalStack    │  │  Apache Kafka    │
                   │  :5433 + pgvec  │  │   S3 :4566      │  │  :9092           │
                   │  files_db       │  │   File Storage  │  │  file-events     │
                   └─────────────────┘  └─────────────────┘  └──────────────────┘
                              │
                   ┌──────────▼──────────────────────────────┐
                   │              AI SDK (library)            │
                   │                                          │
                   │  ResilientLLMClient                      │
                   │    └── CircuitBreaker (Resilience4j)     │
                   │          └── Retry (Resilience4j)        │
                   │                └── OpenAIClient          │
                   │                      ├── GPT-4o-mini     │
                   │                      └── text-embed-3-sm │
                   └──────────────────────────────────────────┘
```

---

## Key Metrics

| Metric | Value |
|---|---|
| Total test cases | **289** across 27 test files |
| JaCoCo coverage gate | **80%** enforced on every build |
| Semantic relevance discrimination | **3.4×** (0.61 relevant vs 0.18 unrelated cosine similarity) |
| Embedding dimensions | **1536** (OpenAI text-embedding-3-small) |
| Rate limit — authenticated | **100 req/min** |
| Rate limit — unauthenticated | **10 req/min** |
| Soft delete recovery window | **30 days** |
| Access token lifetime | **15 min** (900s) |
| Refresh token lifetime | **7 days** |

### Test Coverage by Service

| Service | Test Files | Test Cases |
|---|---|---|
| ai-sdk | 7 | 80 |
| file-service | 6 | 89 |
| user-service | 6 | 63 |
| api-gateway | 5 | 34 |
| notification-service | 3 | 23 |
| **Total** | **27** | **289** |

---

## Tech Stack

| Technology | Version | Role |
|---|---|---|
| Java | 21 | Primary language; virtual threads for AI pipeline |
| Spring Boot | 3.x | Application framework across all services |
| Spring Cloud Gateway | 4.x | API gateway, routing, filter chain |
| Spring Security | 6.x | Stateless JWT filter chain |
| Spring Data JPA | 3.x | ORM for all relational entities |
| Spring Kafka | 3.x | Kafka producer (file-service) and consumer (notification-service) |
| Apache Kafka | 7.6 (Confluent) | Event streaming backbone |
| PostgreSQL | 15 | Primary database for all three services |
| pgvector | pg15 | Vector similarity search on file embeddings |
| Flyway | 9.x | Database schema migrations |
| AWS S3 / LocalStack | 3.x | File object storage |
| Apache Tika | 2.9.2 | Text extraction from PDF, DOCX, TXT |
| OpenAI API | — | GPT-4o-mini (summarization), text-embedding-3-small (search) |
| Anthropic Claude API | — | Alternative LLM provider in AI SDK |
| Resilience4j | 2.x | Circuit Breaker + Retry for LLM calls |
| JTokkit | — | Accurate token counting (cl100k_base) |
| JJWT | 0.12.x | JWT signing and validation |
| BCrypt | — | Password hashing via Spring Security |
| Docker Compose | — | Full local stack orchestration |
| Testcontainers | — | PostgreSQL + pgvector integration tests |
| JaCoCo | — | Code coverage gate (80% minimum) |
| Springdoc OpenAPI | — | Swagger UI for each service |

---

## Getting Started

### Prerequisites

- Docker Desktop (running)
- Java 21+ (for building locally)
- Maven 3.9+ (or use included `mvnw`)
- An OpenAI API key (for AI features; services start without it but AI processing is skipped)

### Clone & Configure

```bash
git clone https://github.com/risrchanish/syncvault.git
cd syncvault
```

Create your `.env` file at the repo root (never committed):

```bash
cp .env.example .env   # then edit with your values
```

`.env` structure (`.env.example`):

```dotenv
# Database credentials
DB_USERNAME=postgres
DB_PASSWORD=your_db_password_here

# JWT signing secret — min 32 characters
JWT_SECRET=your-strong-jwt-secret-at-least-32-chars

# OpenAI — required for AI summarization and semantic search
# Without this, uploads succeed but AI fields remain null
OPENAI_API_KEY=sk-...

# Gmail SMTP — optional, enables email notifications
# Use a Gmail App Password, not your account password
GMAIL_USERNAME=you@gmail.com
GMAIL_APP_PASSWORD=your_app_password_here
```

### Start the Full Stack

```bash
docker compose up -d
```

First run downloads images and builds JARs — allow ~3 minutes. Subsequent starts take ~60 seconds.

Monitor startup:

```bash
docker compose ps          # all services should show "healthy"
docker compose logs -f     # stream all logs
docker compose logs -f file-service   # stream a specific service
```

### Create the S3 Bucket

LocalStack starts empty. Run this once after the stack is healthy:

```bash
aws --endpoint-url=http://localhost:4566 \
    --region us-east-1 \
    s3 mb s3://syncvault-files
```

> Requires the AWS CLI. Alternatively: `docker exec localstack awslocal s3 mb s3://syncvault-files`

### Service URLs

| Service | URL | Notes |
|---|---|---|
| API Gateway | http://localhost:8080 | All client traffic goes here |
| User Service | http://localhost:8081/swagger-ui.html | Direct access + Swagger UI |
| File Service | http://localhost:8082/swagger-ui.html | Direct access + Swagger UI |
| Notification Service | http://localhost:8083/actuator/health | Health check |
| LocalStack S3 | http://localhost:4566 | S3-compatible endpoint |
| Kafka | localhost:29092 | Host access for tools like Offset Explorer |

---

## API Reference

All requests below go through the API Gateway on port 8080.

### Auth Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/auth/register` | No | Register a new user |
| `POST` | `/auth/login` | No | Login, returns access + refresh tokens |
| `POST` | `/auth/refresh` | No | Rotate refresh token, returns new token pair |
| `POST` | `/auth/logout` | No | Revoke refresh token server-side |

**Register**
```bash
curl -s -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"SecurePass1!","fullName":"Jane Doe"}'
```
```json
{ "userId": "3f2a1b...", "message": "User registered successfully" }
```

**Login**
```bash
curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"SecurePass1!"}'
```
```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "expiresIn": 900
}
```

### User Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/users/{userId}/profile` | Bearer token | Get user profile |
| `PUT` | `/users/{userId}/profile` | Bearer token | Update display name |

### File Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/files/upload` | Bearer token | Upload a file (multipart) |
| `GET` | `/files/{fileId}` | Bearer token | Get file info + presigned S3 URL |
| `GET` | `/files` | Bearer token | List all files for authenticated user |
| `PUT` | `/files/{fileId}` | Bearer token | Update file (pass `baseVersion` for conflict detection) |
| `DELETE` | `/files/{fileId}` | Bearer token | Soft delete (recoverable for 30 days) |
| `GET` | `/files/{fileId}/versions` | Bearer token | List all versions |
| `POST` | `/files/{fileId}/restore/{version}` | Bearer token | Restore a previous version |
| `GET` | `/files/{fileId}/summary` | Bearer token | Get AI summary, description, and tags |
| `POST` | `/files/{fileId}/summarize` | Bearer token | Re-trigger AI summarization on demand |
| `GET` | `/files/search?q={query}&limit={n}` | Bearer token | Semantic search across user's files |

**Upload a file**
```bash
TOKEN="eyJ..."
curl -s -X POST http://localhost:8080/files/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/document.pdf"
```
```json
{
  "fileId": "a1b2c3...",
  "fileName": "document.pdf",
  "fileSize": 204800,
  "version": 1,
  "uploadedAt": "2025-04-28T10:00:00Z"
}
```

**Get AI summary** (after ~2–3s async processing)
```bash
curl -s http://localhost:8080/files/$FILE_ID/summary \
  -H "Authorization: Bearer $TOKEN"
```
```json
{
  "aiSummary": "This document discusses...",
  "aiDescription": {"topic": "...", "keyPoints": ["..."]},
  "aiTags": ["finance", "quarterly-report"],
  "aiProcessedAt": "2025-04-28T10:00:03Z"
}
```

**Semantic search**
```bash
curl -s "http://localhost:8080/files/search?q=quarterly+revenue+forecast&limit=5" \
  -H "Authorization: Bearer $TOKEN"
```
```json
[
  { "fileId": "...", "fileName": "Q3-Report.pdf", "relevanceScore": 0.91 },
  { "fileId": "...", "fileName": "Budget-2025.xlsx", "relevanceScore": 0.74 }
]
```

**Update with conflict detection**
```bash
# Pass the version you based your edit on
curl -s -X PUT http://localhost:8080/files/$FILE_ID \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"baseVersion": 2, "fileName": "document_v2.pdf"}'
```
```json
# If another client already updated to version 3:
{
  "status": "CONFLICT",
  "message": "File was modified by another client",
  "conflictCopyName": "document_conflict_1714298400000.pdf"
}
```

---

## Phases Roadmap

### Phase 1 — Core Sync Platform + AI Intelligence ✅ Complete

- Distributed microservices architecture (5 services)
- JWT authentication with token rotation
- File upload, versioning, conflict detection, soft delete
- AI document summarization pipeline (Apache Tika + GPT-4o-mini)
- 1536-dimension semantic embeddings with pgvector search
- Custom resilient AI SDK (OpenAI + Claude, CB + Retry)
- Event-driven notifications via Kafka
- Full Docker Compose stack with LocalStack S3
- 289 automated tests, 80% coverage gate

### Phase 2 — Observability + Caching (Planned)

Phase 2 focuses on making the platform operationally mature. Redis will be introduced as a caching layer for subscription status lookups — currently every file upload triggers a synchronous HTTP call from file-service to user-service to check storage quota; caching that response with a short TTL eliminates the inter-service round trip on the hot path. Prometheus metrics will be exposed from each service via Micrometer and scraped into a Grafana dashboard, giving visibility into upload throughput, AI pipeline latency, Kafka consumer lag, and rate limiter hit rates per service.

### Phase 3 — Advanced AI Features + Local LLM Support (Planned)

Phase 3 expands the AI capabilities significantly. The centrepiece is **RAG-based document chat** — users will be able to ask natural language questions across their entire file library, with answers grounded in retrieved document chunks rather than hallucinated responses. Alongside this: **auto folder organisation** where the AI analyses embedding clusters across a user's files and suggests a folder structure based on content patterns; **duplicate detection** using cosine distance between embeddings to flag near-identical documents uploaded at different times; **multi-language document summaries** so that a French PDF produces a French summary without any prompt engineering; and an **AI cost dashboard** that tracks token usage and estimated spend per user, enabling per-user quotas and abuse detection.

On the provider side, Phase 3 adds **Ollama as a local LLM provider** — a third `LLMClient` implementation that routes requests to a locally running model with zero API cost, which is already a slot in the AI SDK's provider abstraction. Summarisation responses will move to **streaming** so the client receives tokens progressively rather than waiting for the full completion, reducing perceived latency on long documents.

### Phase 4 — Production Infrastructure (Planned)

Phase 4 moves the platform from Docker Compose to a production-grade AWS deployment. Kubernetes manifests will be written for all services with horizontal pod autoscaling on CPU and custom metrics (Kafka consumer lag for notification-service, request rate for api-gateway). The AWS stack targets EC2 for compute, RDS for managed PostgreSQL (with Multi-AZ for the files database), MSK for managed Kafka, real S3 replacing LocalStack, and Route 53 with an Application Load Balancer for traffic management and TLS termination.

---

## Design Decisions

### 1. Separate Database Per Service

Each service owns its own PostgreSQL instance (`users_db`, `files_db`, `notifications_db`) rather than sharing a monolithic schema.

**Why:** True service independence — file-service can use pgvector extensions that user-service has no need for; notification-service can run H2 in tests without fighting other services' Flyway migrations. Each database can be scaled, backed up, and migrated independently.

**Trade-off:** Cross-service queries require HTTP calls (e.g., file-service calling user-service to validate storage quota), not joins. Accepted for a system of this scale.

### 2. Custom AI SDK Instead of a Framework Library

The AI SDK (`com.syncvault:ai-sdk`) is hand-built rather than using a framework like Spring AI or LangChain4j.

**Why:** Full control over the resilience model — Resilience4j's Circuit Breaker wrapping Retry is a deliberate topology that most frameworks don't expose cleanly. The token truncation strategy, provider abstraction, and the exact shape of request/response mapping are all testable in isolation with 80 unit tests. Framework libraries often hide these details behind opaque autoconfiguration.

**Trade-off:** More code to maintain. Justified because the SDK is a learning artefact as much as a production component.

### 3. JWT Validation at the Gateway Only

JWT signatures are verified once at the API Gateway. Downstream services trust the `X-User-Id` header injected by the gateway and do not re-verify the token.

**Why:** Avoids distributing the JWT secret to every service and eliminates redundant crypto work on every hop. The gateway is the trust boundary; inside the cluster, the network is trusted.

**Trade-off:** If a request somehow bypasses the gateway (e.g., direct port access in a misconfigured deployment), downstream services have no independent auth check. Mitigated in production by network policy — downstream ports are not exposed externally.

### 4. Virtual Threads for AI Processing

The AI summarization and embedding pipeline runs on Java 21 virtual threads (`Thread.ofVirtual().name("ai-summary-{id}").start(...)`) rather than a thread pool executor.

**Why:** Both pipelines are I/O-bound — they block on HTTP calls to the OpenAI API. Virtual threads are cheap enough that launching two per upload has negligible overhead and avoids holding a platform thread during the blocking HTTP wait. This keeps the main upload request path fast (returns immediately) while AI processing happens asynchronously.

**Trade-off:** No backpressure mechanism. Under extreme upload load, many concurrent OpenAI calls could exhaust the rate limit. The `ResilientLLMClient` circuit breaker provides a safety valve, but a dedicated executor with bounded concurrency would be more robust at scale.

---

## Payment Integration — Future Plug-in Points

A Payment Service is not built in Phase 1, but the architecture was designed so that adding one requires zero restructuring of any existing service. All four integration points are already coded and waiting — they just need to be connected.

**Touch Point 1 — Subscription tier in the User schema.** The `users` table has a `subscription_plan` column (`FREE` / `PRO` / `ENTERPRISE`) written by Flyway V1 and readable by every service today. When a Payment Service processes a successful charge, it calls `PUT /users/{userId}/subscription` and updates this column. No schema migration needed.

**Touch Point 2 — Storage quota in FileService.** `FileService.getStorageLimit()` currently returns a hardcoded 5 GB for all users. The method is already extracted and isolated — one line change swaps the constant for an HTTP call to the Payment Service that returns the quota for the user's current plan. The rest of `FileService` is unchanged.

**Touch Point 3 — Gateway route already declared.** `api-gateway/application.yaml` already contains a `/payments/**` route entry pointing at `${services.payment-service.url}`. Spinning up a Payment Service on its expected port is all that is needed — the gateway will start routing immediately with no configuration change.

**Touch Point 4 — Payment receipt emails in Notification Service.** The notification-service Kafka consumer is structured to handle any topic. Adding payment receipt emails requires one new `@KafkaListener` method on the `payment.confirmed` topic and one new email template in `EmailNotificationService` — nothing else in the service changes.

## Future Services

**Payment Service (Razorpay / Stripe).** A dedicated service handling subscription billing, charge lifecycle, and webhook processing from the payment provider. It owns its own database for transaction records and integrates with the four touch points above. No existing service needs modification when it is deployed.

**Collaboration Service (WebSocket + Operational Transformation).** A service enabling real-time concurrent editing of documents stored in SyncVault. Clients connect via WebSocket; concurrent edits are reconciled using Operational Transformation so two users editing the same document simultaneously see a consistent result. The conflict detection already built into file-service (optimistic locking) handles the persistence layer — the collaboration service sits in front of it, resolving edit operations before they are committed.

---

## Project Structure

```
syncvault/
├── api-gateway/                  # Spring Cloud Gateway
│   ├── src/main/java/.../
│   │   ├── filter/               # JWT + rate limit global filters
│   │   ├── ratelimit/            # Fixed-window rate limiter
│   │   └── security/             # JwtTokenProvider
│   └── src/test/java/.../        # 34 test cases
│
├── user-service/                 # Auth + user management
│   ├── src/main/java/.../
│   │   ├── controller/           # AuthController, UserController
│   │   ├── service/              # AuthService, UserService
│   │   ├── security/             # JWT filter, provider, UserDetailsService
│   │   └── entity/               # User, RefreshToken
│   └── src/test/java/.../        # 63 test cases
│
├── file-service/                 # Core file operations + AI
│   ├── src/main/java/.../
│   │   ├── controller/           # FileController
│   │   ├── service/              # FileService, DocumentSummarizationService
│   │   │                         # EmbeddingService, FileSearchService
│   │   │                         # DocumentTextExtractor, S3Service
│   │   ├── kafka/                # FileEventProducer, FileEvent
│   │   └── entity/               # FileMetadata, FileVersion
│   └── src/test/java/.../        # 89 test cases
│
├── notification-service/         # Event-driven email
│   ├── src/main/java/.../
│   │   ├── consumer/             # FileEventConsumer (Kafka)
│   │   └── service/              # EmailNotificationService
│   └── src/test/java/.../        # 23 test cases
│
├── ai-sdk/                       # Custom LLM client library
│   ├── src/main/java/.../
│   │   ├── client/               # LLMClient interface
│   │   ├── provider/             # OpenAIClient, ClaudeClient
│   │   ├── resilience/           # ResilientLLMClient
│   │   ├── token/                # TokenCounter
│   │   └── config/               # LLMClientBuilder
│   └── src/test/java/.../        # 80 test cases
│
├── postman/                      # Postman collection + environment
├── docker-compose.yml            # Full stack (all services + infra)
└── .env                          # Local secrets (gitignored)
```

---

## Environment Variables Reference

| Variable | Required | Used By | Notes |
|---|---|---|---|
| `DB_USERNAME` | Yes | All services, all DBs | PostgreSQL username |
| `DB_PASSWORD` | Yes | All services, all DBs | PostgreSQL password |
| `JWT_SECRET` | Yes | user-service, file-service, api-gateway | Min 32 chars for HMAC-SHA256 |
| `OPENAI_API_KEY` | For AI features | file-service | Falls back to `not-configured`; uploads work without it |
| `GMAIL_USERNAME` | Optional | notification-service | Gmail address for SMTP |
| `GMAIL_APP_PASSWORD` | Optional | notification-service | Gmail App Password (not account password) |

---

*Built with Java 21, Spring Boot, Apache Kafka, pgvector, and OpenAI.*
