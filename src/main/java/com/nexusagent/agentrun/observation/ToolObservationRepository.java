package com.nexusagent.agentrun.observation;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.nexusagent.agentrun.application.RunJson;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class ToolObservationRepository {
    private static final String COLUMNS = "id, invocation_id, tool_name, attempt, retry_of, status, created_at, finished_at";
    private final DatabaseClient db;
    private final RunJson json;

    public ToolObservationRepository(DatabaseClient db, RunJson json) { this.db = db; this.json = json; }

    public Flux<ToolObservation> list(UUID run, int limit, int offset) {
        return db.sql("SELECT " + COLUMNS + " FROM agent_tool_calls WHERE run_id=:run ORDER BY created_at, id LIMIT :limit OFFSET :offset")
                .bind("run", run).bind("limit", limit).bind("offset", offset).map(row -> map(row, false)).all();
    }

    public Mono<ToolObservation> find(UUID run, UUID observation) {
        return db.sql("SELECT " + COLUMNS + ", arguments::text AS args, result::text AS result_json "
                        + "FROM agent_tool_calls WHERE run_id=:run AND id=:id")
                .bind("run", run).bind("id", observation).map(row -> map(row, true)).one();
    }

    private ToolObservation map(Readable row, boolean detail) {
        return new ToolObservation(row.get("id", UUID.class), row.get("invocation_id", UUID.class),
                row.get("tool_name", String.class), row.get("attempt", Integer.class), row.get("retry_of", UUID.class),
                row.get("status", String.class), row.get("created_at", OffsetDateTime.class),
                row.get("finished_at", OffsetDateTime.class), detail ? json.parse(row.get("args", String.class)) : null,
                detail ? json.parse(row.get("result_json", String.class)) : null);
    }
}
