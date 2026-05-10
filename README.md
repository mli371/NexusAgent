# NexusAgent

NexusAgent is an Enterprise Knowledge Assistant Backend built as an interview-defensible Java backend project.

Milestone 1 implements the foundation for raw document upload:

- Spring Boot 3.x WebFlux application
- PostgreSQL metadata storage
- Flyway database migration
- MinIO raw file storage
- Redis in local Docker Compose for future milestones
- REST APIs for health, upload, list, and fetch-by-id

Milestone 1 does not implement text extraction, chunking, embeddings, retrieval, query answering, SSE, or Redis-backed state/cache behavior.

## Tech Stack

- Java 17
- Spring Boot 3.3.x
- Spring WebFlux
- Project Reactor
- PostgreSQL 16 with PgVector
- R2DBC PostgreSQL
- Flyway
- MinIO
- Redis
- Docker Compose
- JUnit 5
- Testcontainers where Docker is available

## Prerequisites

- Java 17 or newer
- Maven 3.9+
- Docker Desktop or another Docker runtime for local services

The code is compiled with Java release 17 even if a newer JDK is installed locally.

## Local Configuration

Copy the example environment file:

```bash
cp .env.example .env
```

`.env.example` is the committed template that shows safe local defaults. `.env` is your local machine-specific copy and is ignored by git so credentials, port overrides, and local settings are not committed.

Spring Boot imports `.env` during local runs through `spring.config.import`, and Docker Compose also reads the same `.env` file. This keeps the PostgreSQL host, port, database, user, and password aligned between Compose and the application.

Important defaults:

```text
PostgreSQL host: localhost
PostgreSQL host port: 5432
PostgreSQL container port: 5432
PostgreSQL database: nexusagent
PostgreSQL user/password: nexus/nexus
MinIO API: http://localhost:9000
MinIO console: http://localhost:9001
MinIO user/password: minioadmin/minioadmin123
Bucket: nexus-documents
Redis: localhost:6379
Application upload max: 25 MiB
Multipart disk usage per part: 30 MiB
Multipart in-memory threshold: 1 MiB
Multipart max parts: 4
```

## Start Local Dependencies

```bash
docker compose up -d
```

Check containers:

```bash
docker compose ps
```

PostgreSQL should show as healthy. You can verify it directly:

```bash
docker compose exec postgres pg_isready -U nexus -d nexusagent
```

MinIO console:

```text
http://localhost:9001
```

## Run The App

After PostgreSQL is healthy:

```bash
mvn spring-boot:run
```

The app starts on:

```text
http://localhost:8080
```

Flyway runs automatically at startup and creates the `documents` table.

Flyway uses:

```text
jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}
```

Runtime persistence uses:

```text
r2dbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}
```

Both use the same `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, and `POSTGRES_PASSWORD` values.

## API Examples

Health:

```bash
curl http://localhost:8080/api/v1/health
```

Create a sample document:

```bash
printf "NexusAgent milestone one sample document.\n" > sample.txt
```

Upload it:

```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@sample.txt;type=text/plain"
```

Example response shape:

```json
{
  "id": "7bfc50c2-81f9-49df-872f-f401f8fbff20",
  "originalFilename": "sample.txt",
  "contentType": "text/plain",
  "sizeBytes": 44,
  "sha256": "a SHA-256 hex digest",
  "minioBucket": "nexus-documents",
  "minioObjectKey": "documents/7bfc50c2-81f9-49df-872f-f401f8fbff20/sample.txt",
  "status": "STORED",
  "createdAt": "2026-05-07T12:00:00Z",
  "updatedAt": "2026-05-07T12:00:00Z"
}
```

Fetch one document:

```bash
curl http://localhost:8080/api/v1/documents/{document-id}
```

List documents:

```bash
curl "http://localhost:8080/api/v1/documents?limit=50&offset=0"
```

## Run Tests

```bash
mvn test
```

The test suite includes:

- Application context startup test
- Document upload service unit tests
- PostgreSQL metadata persistence integration test

The persistence integration test uses Testcontainers with the `pgvector/pgvector:pg16` image. If Docker is not available to Testcontainers, that test is skipped.

## Troubleshooting Local PostgreSQL

### Port 5432 Is Already Allocated

If another local PostgreSQL is already using port `5432`, change `POSTGRES_PORT` in `.env`:

```text
POSTGRES_HOST=localhost
POSTGRES_PORT=55432
POSTGRES_DB=nexusagent
POSTGRES_USER=nexus
POSTGRES_PASSWORD=nexus
```

Then recreate the Compose service:

```bash
docker compose down
docker compose up -d postgres
docker compose ps postgres
mvn spring-boot:run
```

Because Spring Boot imports `.env`, Flyway and R2DBC will use the same new host port that Docker Compose exposes.

### PostgreSQL Container Is Not Healthy

Check status:

```bash
docker compose ps postgres
```

Check logs:

```bash
docker compose logs postgres
```

Run the readiness probe manually:

```bash
docker compose exec postgres pg_isready -U nexus -d nexusagent
```

If the container is unhealthy because credentials or database names changed, reset the local volume:

```bash
docker compose down -v
docker compose up -d postgres
```

This deletes local PostgreSQL data. Use it only for local development.

### Flyway Connection Refused

`Connection to localhost:5432 refused` means the app cannot reach PostgreSQL on the configured host/port.

Check the effective Compose port:

```bash
docker compose config | grep -A5 "target: 5432"
```

Check that PostgreSQL is running and healthy:

```bash
docker compose ps postgres
docker compose exec postgres pg_isready -U nexus -d nexusagent
```

Then confirm `.env` matches the app configuration:

```bash
grep '^POSTGRES_' .env
```

The default working local values are:

```text
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=nexusagent
POSTGRES_USER=nexus
POSTGRES_PASSWORD=nexus
```

## Milestone 1 Design

Upload flow:

```text
Client multipart upload
  -> WebFlux controller
  -> temporary local upload file
  -> file inspection: size + SHA-256
  -> MinIO raw object storage
  -> PostgreSQL document metadata row
  -> API response
```

MinIO stores the raw file. PostgreSQL stores source-of-truth metadata. Redis is available in Docker Compose for later milestones but is not used in Milestone 1.

Flyway uses the JDBC PostgreSQL driver because Flyway is a blocking schema migration tool. Runtime metadata persistence uses R2DBC through Spring's reactive `DatabaseClient`.

The local PostgreSQL image is `pgvector/pgvector:pg16`, which includes PgVector support. The migration uses `CREATE EXTENSION IF NOT EXISTS vector`, so re-running migrations against an existing database is safe for that extension statement.

## Upload Limits

There are two layers of upload limits:

- Spring WebFlux multipart limits control request parsing and temporary multipart storage.
- `nexus.upload.max-file-size-bytes` is the application-level validation after the file is received and inspected.

Defaults:

```text
spring.webflux.multipart.max-in-memory-size=1MB
spring.webflux.multipart.max-disk-usage-per-part=30MB
spring.webflux.multipart.max-parts=4
nexus.upload.max-file-size-bytes=26214400
```

The multipart disk limit is slightly larger than the application file limit so the app can return its own validation error for oversized files near the boundary.

## Blocking Client Trade-Off

The MinIO Java client and local file hashing are blocking operations. Milestone 1 isolates them behind service boundaries and runs them on Reactor `boundedElastic`:

- `MinioObjectStorageService`
- `UploadedFileInspector`
- temporary file creation/deletion inside `DocumentUploadService`

This keeps the WebFlux request flow from doing blocking file/object-storage work on event-loop threads. The trade-off is that Milestone 1 buffers uploads to a temporary local file instead of streaming directly from the HTTP request into object storage.

Temporary file cleanup is handled with Reactor `usingWhen`, which is the reactive equivalent of a composed finally block. It runs cleanup on completion, error, and cancellation.

If MinIO object storage succeeds but PostgreSQL metadata persistence fails, the upload service now attempts best-effort deletion of the just-created MinIO object. If that cleanup fails, the original metadata persistence error is preserved and the cleanup failure is logged.

Flyway uses JDBC at startup because Flyway is a blocking migration tool. Request-time document metadata reads and writes use R2DBC through Spring's reactive `DatabaseClient`.

## Known Limitations

- No text extraction yet.
- No parent-child chunking yet.
- No embeddings or PgVector vector search yet.
- No retrieval, RRF, reranking, context construction, query answering, or SSE yet.
- Redis is only started by Docker Compose; the application does not use it yet.
- Uploads are written to a temporary local file before MinIO storage.
- If MinIO upload succeeds but PostgreSQL persistence fails, Milestone 1 attempts best-effort MinIO cleanup. An orphaned object can still remain if cleanup also fails.
- There is no authentication, tenant isolation, malware scanning, file type policy, or production observability yet.
- Docker images are intended for local development, not production deployment.

## Future Improvements

- Add an outbox/reconciliation flow for partial upload failures that remain after best-effort cleanup.
- Add document status transitions for extraction and ingestion.
- Add text extraction and chunk persistence in Milestone 2.
- Add PgVector embedding storage in Milestone 3.
- Add authentication and tenant-aware access control in a later hardening milestone.
