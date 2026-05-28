# Troubleshooting

## Docker Is Not Running

Symptoms:

- Testcontainers integration tests are skipped.
- `docker compose ps` fails.
- Local dependencies are unreachable.

Fix:

```bash
docker compose up -d
docker compose ps
```

## PostgreSQL Port Is Already Used

Set a different local host port in `.env`:

```text
POSTGRES_PORT=55432
```

Restart:

```bash
docker compose down
docker compose up -d postgres
mvn spring-boot:run
```

Spring Boot reads `.env`, so Flyway and R2DBC use the same port.

## Flyway Connection Refused

Check PostgreSQL health:

```bash
docker compose ps postgres
docker compose exec postgres pg_isready -U nexus -d nexusagent
```

Check `.env`:

```bash
grep '^POSTGRES_' .env
```

## Redis Disabled

Set:

```text
NEXUS_REDIS_ENABLED=false
```

The app uses in-memory/no-op fallback services. Query and agent APIs still work, but cache/state/tool-output behavior is not Redis-backed.

## Redis Keys Are Missing

Run a query with `sessionId` and `debug=true`, then inspect:

```bash
scripts/demo.sh query
scripts/demo.sh redis-keys
```

Redis writes can be skipped if values exceed configured size limits.

## MinIO Console

Open:

```text
http://localhost:9001
```

Default local credentials come from `.env.example`.

## Unsupported Document Type

Current extraction supports plain text and Markdown-like files only. Use:

- `.txt`
- `.md`
- `.markdown`
- `text/plain`
- Markdown content types

PDF and Word files are future work.

## Empty Retrieval Results

Check the document lifecycle:

```bash
scripts/demo.sh upload
scripts/demo.sh chunk
scripts/demo.sh embed
scripts/demo.sh retrieval-debug
```

Retrieval requires chunked and embedded child chunks.

## SSE With Browser Clients

The SSE endpoint is `POST /api/v1/query/stream` because it accepts a JSON request body. Browser `EventSource` normally supports `GET`; use `curl`, `fetch`, a server-side client, or add a GET adapter later.
