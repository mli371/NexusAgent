# Milestone 1 Learning Note: Foundation + Raw Document Upload

## Problem This Milestone Solves

Before NexusAgent can extract text, chunk documents, embed content, or retrieve answers, it needs a reliable ingestion foundation.

Milestone 1 answers two questions:

1. Where does the original uploaded file live?
2. Where does the backend store source-of-truth metadata about that file?

The answer is intentionally simple:

- MinIO stores the raw uploaded file.
- PostgreSQL stores document metadata.
- Redis is present for later milestones but not used yet.

## What Was Built

This milestone adds:

- Spring Boot 3.x WebFlux application
- Docker Compose for PostgreSQL with PgVector, Redis, and MinIO
- Flyway migration for the `documents` table
- `POST /api/v1/documents`
- `GET /api/v1/documents/{id}`
- `GET /api/v1/documents`
- `GET /api/v1/health`
- Unit tests for upload behavior
- Integration test for metadata persistence when Docker is available

## Main Classes And Responsibilities

`DocumentController`

Receives HTTP requests and delegates to application services. It does not contain storage or database logic.

`DocumentUploadService`

Coordinates the upload flow. It validates the file, uses a temporary local file, asks MinIO storage to store the raw object, and saves metadata in PostgreSQL.

`UploadedFileInspector`

Computes file size and SHA-256. This uses blocking file I/O, so it runs on Reactor `boundedElastic`.

`ObjectStorageService`

Small interface for raw object storage. It lets the application service depend on a storage boundary instead of directly depending on MinIO. It supports storing raw objects and deleting them for best-effort cleanup if metadata persistence fails.

`MinioObjectStorageService`

MinIO implementation of `ObjectStorageService`. It creates the bucket if needed and stores the raw file. The MinIO Java client is blocking, so this service runs storage work on Reactor `boundedElastic`.

`DocumentRepository`

Uses Spring's reactive `DatabaseClient` to persist and read metadata from PostgreSQL.

`ObjectKeyFactory`

Builds stable object keys such as:

```text
documents/{documentId}/{filename}
```

It also sanitizes filenames so object keys do not contain path segments from client-provided filenames.

## Data Flow

```text
POST /api/v1/documents
  -> DocumentController
  -> DocumentUploadService
  -> temporary local file
  -> UploadedFileInspector
  -> MinioObjectStorageService
  -> DocumentRepository
  -> response DTO
```

The database row is created only after MinIO storage succeeds.

If the database insert fails after object storage succeeds, the service attempts to delete the just-created MinIO object. The original database error remains the API failure. Cleanup failure is logged but does not replace the original error.

## Database Shape

Milestone 1 creates one table:

```text
documents
  id
  original_filename
  content_type
  size_bytes
  sha256
  minio_bucket
  minio_object_key
  status
  created_at
  updated_at
```

The migration also enables the PgVector extension so the database is ready for later embedding milestones.

The Docker Compose PostgreSQL image is `pgvector/pgvector:pg16`, which includes the PgVector extension files. The migration uses:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

That statement is idempotent: if the extension is already installed in the database, PostgreSQL does not fail or recreate it.

## Flyway JDBC Versus Runtime R2DBC

This project intentionally uses two PostgreSQL connection styles:

- Flyway uses JDBC.
- Runtime metadata persistence uses R2DBC.

Flyway is a migration tool that runs blocking schema updates at application startup. It expects a JDBC connection URL like:

```text
jdbc:postgresql://localhost:5432/nexusagent
```

The application repository uses Spring's reactive `DatabaseClient` with R2DBC during request handling. It uses a URL like:

```text
r2dbc:postgresql://localhost:5432/nexusagent
```

This split is normal in Spring reactive applications. Schema migration happens at startup through Flyway; request-time persistence stays reactive through R2DBC.

## Why This Design Is Reasonable

Raw storage and metadata are separated because they have different responsibilities:

- MinIO is good at storing file bytes.
- PostgreSQL is good at querying metadata and maintaining source-of-truth records.

The service does not extract text during upload yet. That keeps this milestone narrow and prevents the API from pretending ingestion is complete.

The upload service coordinates the workflow but does not know MinIO details. That makes it easier to test and easier to replace storage later.

## Blocking Dependency Trade-Off

Milestone 1 uses blocking APIs for:

- temporary file creation/deletion
- SHA-256 file hashing
- MinIO object storage

These operations are isolated behind service methods and scheduled on Reactor `boundedElastic`.

This is acceptable for the milestone because the goal is a clear local backend foundation. A more advanced implementation might stream directly to object storage and compute checksums without writing the whole upload to a temporary file first.

Temporary file cleanup uses Reactor `usingWhen`. This is the reactive version of a finally block: cleanup runs after success, after error, and on cancellation.

## Upload Size Validation

Milestone 1 has two layers of upload limits:

- WebFlux multipart limits protect request parsing and temporary multipart storage.
- Application validation rejects empty files and files larger than `nexus.upload.max-file-size-bytes`.

Defaults:

```text
spring.webflux.multipart.max-in-memory-size=1MB
spring.webflux.multipart.max-disk-usage-per-part=30MB
spring.webflux.multipart.max-parts=4
nexus.upload.max-file-size-bytes=26214400
```

The application file limit is 25 MiB. The multipart disk limit is 30 MiB so the server can parse near-boundary uploads and return a clear application validation error. Much larger uploads may be rejected earlier by WebFlux multipart parsing.

## What Is Simplified For Now

- Uploads are stored through a temporary local file.
- There is only one document status: `STORED`.
- Redis is not used by the application.
- There is no text extraction, chunking, embedding, retrieval, or query answering.
- There is no authentication or tenant-aware access control.
- Partial failure cleanup is best effort. If MinIO succeeds and PostgreSQL fails, the service tries to delete the MinIO object, but an orphaned object may remain if cleanup also fails.

## How To Extend Later

Milestone 2 should add text extraction and parent-child chunk persistence. The `documents` table can keep representing the source document, while new chunk tables represent extracted content.

Milestone 3 should add embeddings for child chunks and store them with PgVector.

Later milestones can add retrieval, reranking, context construction, SSE query flow, and Redis-backed cache/state.

## Interview Defense

In an interview, explain Milestone 1 like this:

> I started by separating raw file storage from metadata. MinIO owns the bytes, PostgreSQL owns the source-of-truth record. The upload API stores the file first, then writes metadata with the MinIO bucket and object key. I kept extraction, chunking, embeddings, and retrieval out of this milestone so the API does not imply capabilities that are not implemented yet.

Key points to defend:

- WebFlux is used at the API layer, but blocking MinIO and file I/O are isolated on `boundedElastic`.
- The upload service depends on an `ObjectStorageService` interface, which keeps MinIO as an integration detail.
- PostgreSQL metadata is accessed through reactive `DatabaseClient`.
- Flyway uses JDBC at startup for schema migration, while runtime persistence uses R2DBC during request handling.
- PgVector is enabled early because the same local PostgreSQL instance will support embeddings in Milestone 3.
- `CREATE EXTENSION IF NOT EXISTS vector` is idempotent, and the Docker image includes PgVector support.
- Redis is intentionally not used yet; it is only present in Docker Compose for future milestones.

## Commands

Start dependencies:

```bash
docker compose up -d
```

Run the app:

```bash
mvn spring-boot:run
```

Upload a sample file:

```bash
printf "NexusAgent milestone one sample document.\n" > sample.txt
curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@sample.txt;type=text/plain"
```

Run tests:

```bash
mvn test
```

## Known Limitations

- Text extraction is not implemented.
- Chunking is not implemented.
- Embeddings and PgVector search are not implemented.
- Retrieval, RRF, reranking, and context construction are not implemented.
- Query API and SSE are not implemented.
- Redis-backed state/cache is not implemented.
- Local upload buffering is simple but not ideal for very large files.
- Orphaned MinIO objects can still occur if metadata persistence fails after object storage succeeds and best-effort cleanup also fails.
