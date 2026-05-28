package com.nexusagent.enterprise.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class AuditEventRepository {

    private final DatabaseClient databaseClient;

    public AuditEventRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<AuditEvent> save(AuditEvent event) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO audit_events (
                            id,
                            tenant_id,
                            actor_id,
                            trace_id,
                            event_type,
                            resource_type,
                            resource_id,
                            document_id,
                            metadata_json,
                            created_at
                        )
                        VALUES (
                            :id,
                            :tenantId,
                            :actorId,
                            :traceId,
                            :eventType,
                            :resourceType,
                            :resourceId,
                            :documentId,
                            CAST(:metadataJson AS jsonb),
                            :createdAt
                        )
                        RETURNING
                            id,
                            tenant_id,
                            actor_id,
                            trace_id,
                            event_type,
                            resource_type,
                            resource_id,
                            document_id,
                            metadata_json::text AS metadata_json,
                            created_at
                        """)
                .bind("id", event.id())
                .bind("tenantId", event.tenantId())
                .bind("actorId", event.actorId())
                .bind("traceId", event.traceId())
                .bind("eventType", event.eventType().name())
                .bind("resourceType", event.resourceType())
                .bind("resourceId", event.resourceId())
                .bind("metadataJson", event.metadataJson())
                .bind("createdAt", event.createdAt());
        spec = event.documentId() == null
                ? spec.bindNull("documentId", UUID.class)
                : spec.bind("documentId", event.documentId());
        return spec.map(this::mapRow)
                .one();
    }

    private AuditEvent mapRow(Row row, RowMetadata rowMetadata) {
        return new AuditEvent(
                row.get("id", UUID.class),
                row.get("tenant_id", String.class),
                row.get("actor_id", String.class),
                row.get("trace_id", String.class),
                AuditEventType.valueOf(row.get("event_type", String.class)),
                row.get("resource_type", String.class),
                row.get("resource_id", UUID.class),
                row.get("document_id", UUID.class),
                row.get("metadata_json", String.class),
                row.get("created_at", OffsetDateTime.class)
        );
    }
}
