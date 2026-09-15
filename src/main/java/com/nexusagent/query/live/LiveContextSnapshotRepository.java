package com.nexusagent.query.live;

import java.util.List;
import java.util.UUID;

import com.nexusagent.common.error.OperationException;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class LiveContextSnapshotRepository {
    private final DatabaseClient database;

    public LiveContextSnapshotRepository(DatabaseClient database) { this.database = database; }

    Mono<List<DocumentVersion>> read(LiveQueryInput input) {
        return database.sql("""
                SELECT id, retrieval_revision FROM documents
                WHERE id IN (:ids) AND tenant_id = :tenant AND (visibility = 'TENANT' OR owner_id = :actor)
                ORDER BY id
                """).bind("ids", input.documentIds()).bind("tenant", input.context().tenantId())
                .bind("actor", input.context().actorId())
                .map(row -> new DocumentVersion(row.get("id", UUID.class), row.get("retrieval_revision", Long.class)))
                .all().collectList().flatMap(rows -> rows.size() == input.documentIds().size() ? Mono.just(rows)
                        : Mono.error(new OperationException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_ACCESSIBLE",
                                "A selected document does not exist or is not accessible")));
    }

    Mono<Void> assertCurrent(LiveQueryInput input, List<DocumentVersion> expected) {
        return read(input).flatMap(actual -> actual.equals(expected) ? Mono.empty() : Mono.error(LiveQueryGuard.changed()));
    }

    record DocumentVersion(UUID documentId, long revision) { }
}
