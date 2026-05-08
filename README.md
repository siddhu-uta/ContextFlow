# ContextFlow

A production-grade, multi-tenant AI document intelligence platform. Upload PDFs, DOCX, or text files; ask questions in natural language; get streaming answers grounded in your documents with source citations.

Built as a portfolio project demonstrating distributed systems, async event pipelines, vector search, polyglot microservices, and production observability — the patterns used at scale in real companies.

---

## Architecture

```
                        ┌─────────────────────────────────────────┐
                        │              Clients / Frontend          │
                        └────────────────────┬────────────────────┘
                                             │ HTTPS
                        ┌────────────────────▼────────────────────┐
                        │            API Gateway  :8080            │
                        │  JWT validation · Redis rate limiting    │
                        │  Header sanitisation · Route fanout      │
                        └───┬──────────────┬───────────────┬──────┘
                            │              │               │
               ┌────────────▼──┐  ┌────────▼──────┐  ┌───▼────────────┐
               │ Auth Service  │  │Ingestion Svc  │  │ Query Service  │
               │    :8081      │  │    :8082       │  │    :8084       │
               │ JWT · Tenants │  │ S3 upload      │  │ RAG assembly   │
               │ Refresh tokens│  │ Kafka publish  │  │ SSE streaming  │
               └──────┬────────┘  └───────┬────────┘  └───────┬────────┘
                      │                   │                    │
               ┌──────▼────────┐  ┌───────▼───────┐  ┌───────▼────────┐
               │  PostgreSQL   │  │  Apache Kafka  │  │     Redis      │
               │  + pgvector   │  │   (KRaft)      │  │ cache · rate   │
               │  auth schema  │  │document.upload │  │ limit · tokens │
               │ingestion schema│  └───────┬────────┘  └────────────────┘
               └───────────────┘          │
                                  ┌────────▼────────────┐
                                  │  Embedding Worker   │
                                  │      :8083 (Python) │
                                  │  sentence-transform │
                                  │  pgvector HNSW write│
                                  └─────────────────────┘

         Observability: Jaeger (traces) · Prometheus (metrics) · Grafana (dashboards)
```

### Request flows

**Document ingestion** — async, decoupled from the HTTP lifecycle:
```
POST /api/v1/documents/upload
  → Gateway validates JWT
  → Ingestion Service validates file type, uploads to S3, saves Document(PENDING), publishes to Kafka
  → Returns job ID immediately (202 Accepted)
  → Embedding Worker consumes event: extract text → chunk → embed → write to pgvector → Document(COMPLETED)
  → Client polls GET /api/v1/documents/jobs/{jobId}/status
```

**RAG query** — streaming, tenant-isolated:
```
POST /api/v1/query  →  SSE stream
  → Gateway rate-limits (2 req/s per tenant), validates JWT
  → Query Service checks semantic cache (Redis, SHA-256 key)
  → On miss: embed question via embedding-worker /embed
  → pgvector cosine similarity search (top-5 chunks, tenant-scoped)
  → Send "sources" SSE event with citations
  → Stream LLM response token-by-token via SSE
  → Cache answer + publish query.executed Kafka event
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| API Gateway | Spring Cloud Gateway 2023.0.3, WebFlux/Netty |
| Auth Service | Spring Boot 3.3.5, Spring Security, JJWT 0.12.5 |
| Ingestion Service | Spring Boot 3.3.5, Spring Kafka, AWS SDK v2 |
| Query Service | Spring Boot 3.3.5, LangChain4j 0.35.0, SSE |
| Embedding Worker | Python 3.12, FastAPI, sentence-transformers, kafka-python |
| Database | PostgreSQL 16 + pgvector (HNSW index, 384-dim) |
| Message broker | Apache Kafka 3.7 (KRaft, no Zookeeper) |
| Cache | Redis 7 (semantic cache, token bucket rate limiting, JWT blocklist) |
| Object storage | AWS S3 / LocalStack for local dev |
| Embedding model | `all-MiniLM-L6-v2` (384-dim, ~80MB, CPU-friendly) |
| Tracing | OpenTelemetry Java agent + OTLP → Jaeger |
| Metrics | Micrometer + Prometheus + Grafana |
| Containers | Docker Compose (dev) · Helm + KEDA (Kubernetes) |
| CI/CD | GitHub Actions → ECR → EKS via Helm |

---

## Running Locally

### Prerequisites
- Docker and Docker Compose
- Java 21 (for running Java services outside Docker)
- Python 3.12 (for running the embedding worker outside Docker)

### 1. Start infrastructure

```bash
# Postgres, Redis, Kafka, LocalStack, Jaeger, Prometheus, Grafana
docker compose up -d
```

Wait for all services to be healthy:
```bash
docker compose ps
```

### 2. Build the embedding worker image

This downloads the ~80MB sentence-transformers model into the image layer (done once):

```bash
docker compose build embedding-worker
docker compose up -d embedding-worker
```

### 3. Start the Java services

Run each in a separate terminal (or use your IDE):

```bash
# Terminal 1 — Auth Service
cd auth-service && mvn spring-boot:run

# Terminal 2 — Ingestion Service
cd ingestion-service && mvn spring-boot:run

# Terminal 3 — Query Service
cd query-service && mvn spring-boot:run

# Terminal 4 — API Gateway
cd api-gateway && mvn spring-boot:run
```

### Full Docker stack (optional)

Build all services and run everything in containers:

```bash
docker compose --profile full build
docker compose --profile full up -d
```

---

## Quick Demo

```bash
BASE=http://localhost:8090

# 1. Register a tenant (creates org + admin user)
curl -s -X POST $BASE/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"organizationName":"Acme","slug":"acme","adminEmail":"admin@acme.com","adminPassword":"password123"}' \
  | jq .

# 2. Login and grab the access token
TOKEN=$(curl -s -X POST $BASE/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@acme.com","password":"password123"}' | jq -r .accessToken)

# 3. Upload a document
JOB=$(curl -s -X POST $BASE/api/v1/documents/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/your/document.pdf" | jq -r .jobId)

# 4. Poll until COMPLETED
curl -s "$BASE/api/v1/documents/jobs/$JOB/status" \
  -H "Authorization: Bearer $TOKEN" | jq .status

# 5. Query your document (streaming — watch tokens arrive)
curl -N -X POST $BASE/api/v1/query \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"question":"What are the main findings in this document?"}' \
  --no-buffer
```

To use a real LLM instead of the mock streamer:
```bash
export OPENAI_API_KEY=sk-...
export LLM_MODE=openai
# Restart query-service
```

---

## Observability

After `docker compose up -d`:

| UI | URL | Credentials |
|---|---|---|
| Grafana dashboards | http://localhost:3000 | admin / contextflow |
| Jaeger traces | http://localhost:16686 | — |
| Prometheus | http://localhost:9090 | — |

The **ContextFlow Overview** dashboard is pre-provisioned and shows HTTP latency (p95/p50 by service), query execution time per tenant, semantic cache hit rate, Kafka consumer lag, embedding throughput, and JVM health.

---

## Project Structure

```
ContextFlow/
├── api-gateway/          # Spring Cloud Gateway — JWT validation, rate limiting
├── auth-service/         # Tenant registration, JWT issuance, refresh token rotation
├── ingestion-service/    # File upload → S3 → Kafka publish
├── query-service/        # Vector search, SSE streaming, semantic cache, LLM
├── embedding-worker/     # Python — Kafka consumer, chunking, pgvector writes
├── helm/                 # Kubernetes Helm charts (one per service, KEDA for worker)
├── observability/
│   ├── prometheus/       # Scrape config for all services
│   └── grafana/          # Pre-provisioned dashboards and datasources
├── scripts/
│   ├── init-db.sql       # CREATE EXTENSION vector; CREATE SCHEMA auth/ingestion
│   └── localstack-init.sh# Create S3 bucket on LocalStack startup
├── .github/workflows/
│   └── ci.yml            # Test → Docker build → ECR push → EKS Helm deploy
└── docker-compose.yml
```

---

## Running Tests

**Java services** (requires Docker for Testcontainers — Postgres and Redis spin up automatically):

```bash
# All services
mvn test

# Single service
mvn -pl auth-service test
mvn -pl api-gateway test
mvn -pl query-service test
mvn -pl ingestion-service test
```

**Python embedding worker:**

```bash
cd embedding-worker
pip install -r requirements.txt pytest pytest-asyncio httpx
pytest tests/ -v
```

---

## Key Design Decisions

**Why Kafka for ingestion?** Embedding a document takes 15–30 seconds. Doing it synchronously inside the HTTP request would time out. Kafka decouples the upload acknowledgement from the embedding pipeline. The client gets a job ID immediately and polls for status.

**Why pgvector instead of a dedicated vector DB?** Keeping vectors in Postgres means chunk metadata, document records, and embeddings live in the same ACID transaction. Joins are free. No extra infrastructure to operate. The HNSW index gives sub-millisecond ANN search at this scale.

**Why semantic cache?** LLM calls are slow (~2s) and expensive. The same question asked twice by the same tenant (normalized, lowercased, SHA-256 keyed) returns the cached answer word-by-word in milliseconds — preserving the streaming UX while bypassing the full pipeline.

**Why a single embedding model for both indexing and querying?** The query-service calls the embedding-worker's `/embed` endpoint rather than running its own model instance. This guarantees query vectors and document chunk vectors are always in the same vector space — a subtle but critical correctness requirement.

**Why centralize JWT validation in the gateway?** In Phases 1–3, each service validated JWTs independently. Phase 4 moves this to the gateway, where the Redis blocklist check also happens. Downstream services trust the `X-User-Id`/`X-Tenant-Id`/`X-User-Role` headers injected by the gateway — headers that are stripped from any client-supplied values before routing.
