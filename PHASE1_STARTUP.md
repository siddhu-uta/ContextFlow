# Phase 1 — Startup Guide

## Prerequisites
- Java 21
- Maven 3.9+
- Docker Desktop running

---

## Step 1 — Start Infrastructure

```bash
docker compose up -d
```

Wait ~30 seconds for all health checks to pass:

```bash
docker compose ps   # all should show "healthy"
```

This starts:
- PostgreSQL 16 + pgvector on port `5432`
- Redis 7 on port `6379`
- Kafka (KRaft, no Zookeeper) on port `9092`
- LocalStack (S3) on port `4566`

The `init-db.sql` script runs automatically and creates the `auth` and `ingestion` schemas, plus the `vector` extension.

---

## Step 2 — Build the project

```bash
cd /path/to/ContextFlow
mvn clean install -DskipTests
```

---

## Step 3 — Start Auth Service

```bash
cd auth-service
mvn spring-boot:run
# Starts on http://localhost:8081
```

Flyway runs on startup and creates all tables in the `auth` schema.

---

## Step 4 — Start Ingestion Service

```bash
cd ingestion-service
mvn spring-boot:run
# Starts on http://localhost:8082
```

---

## Step 5 — Smoke Test the API

### Register a new tenant (creates org + admin user, returns JWT)
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "organizationName": "Acme Corp",
    "slug": "acme",
    "adminEmail": "admin@acme.com",
    "adminPassword": "secret123"
  }' | jq .
```

Copy the `accessToken` from the response.

### Login
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@acme.com", "password": "secret123"}' | jq .
```

### Add a viewer user (admin only)
```bash
curl -s -X POST http://localhost:8081/api/v1/tenants/users \
  -H "Authorization: Bearer <ACCESS_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"email": "viewer@acme.com", "password": "pass1234", "role": "VIEWER"}' | jq .
```

### Upload a document
```bash
curl -s -X POST http://localhost:8082/api/v1/documents/upload \
  -H "Authorization: Bearer <ACCESS_TOKEN>" \
  -F "file=@/path/to/sample.pdf" | jq .
```

Copy the `jobId` from the response.

### Check processing status
```bash
curl -s http://localhost:8082/api/v1/documents/jobs/<JOB_ID>/status \
  -H "Authorization: Bearer <ACCESS_TOKEN>" | jq .
```

Status will be `PENDING` — Phase 2 (embedding worker) will move it to `COMPLETED`.

### List documents
```bash
curl -s "http://localhost:8082/api/v1/documents?page=0&size=10" \
  -H "Authorization: Bearer <ACCESS_TOKEN>" | jq .
```

### Logout (blocklists the JWT in Redis)
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/logout \
  -H "Authorization: Bearer <ACCESS_TOKEN>"
```

---

## Verify S3 upload landed

```bash
aws --endpoint-url=http://localhost:4566 s3 ls s3://contextflow-documents/ --recursive
```

You should see: `<tenantId>/<documentId>/raw.pdf`

---

## Verify Kafka event was published

```bash
docker exec contextflow-kafka \
  kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic document.uploaded \
  --from-beginning \
  --max-messages 5
```

---

## Common issues

| Problem | Fix |
|---|---|
| `Flyway migration failed` | Run `docker compose down -v && docker compose up -d` to reset DB |
| `Connection refused :5432` | Wait for the postgres container healthcheck to pass |
| `S3 bucket not found` | Check `docker logs contextflow-localstack` — init script may have failed |
| `JWT invalid` | Make sure both services use the same `JWT_SECRET` env var |

---

## What's next — Phase 2

Phase 2 adds the **Python Embedding Worker**:
- Consumes `document.uploaded` from Kafka
- Extracts text from PDF/DOCX/TXT
- Chunks with sliding-window overlap
- Calls sentence-transformers to generate embeddings
- Writes vectors to pgvector
- Updates document status to `COMPLETED`
