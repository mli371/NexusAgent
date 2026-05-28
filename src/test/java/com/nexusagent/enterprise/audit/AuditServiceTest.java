package com.nexusagent.enterprise.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.common.context.RequestContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuditServiceTest {

    @Test
    void writesAuditEventWithoutRawSensitiveMetadata() {
        AuditEventRepository repository = org.mockito.Mockito.mock(AuditEventRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-05-27T12:00:00Z"), ZoneOffset.UTC);
        AuditService service = new AuditService(repository, new ObjectMapper(), clock);
        RequestContext context = new RequestContext("tenant-a", "actor-1");
        UUID resourceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        when(repository.save(any(AuditEvent.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.record(
                        context,
                        "trace-1",
                        AuditEventType.QUERY_EXECUTED,
                        "query",
                        resourceId,
                        documentId,
                        Map.of(
                                "questionHash", service.hashSensitiveValue("What is the security policy?"),
                                "documentIdCount", 1,
                                "rawDocumentText", "private source text",
                                "finalContextText", "full retrieved context",
                                "embeddingVector", "[0.1, 0.2, 0.3]",
                                "safeLongValue", "x".repeat(300)
                        )
                ))
                .verifyComplete();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo("tenant-a");
        assertThat(event.actorId()).isEqualTo("actor-1");
        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.eventType()).isEqualTo(AuditEventType.QUERY_EXECUTED);
        assertThat(event.resourceType()).isEqualTo("query");
        assertThat(event.resourceId()).isEqualTo(resourceId);
        assertThat(event.documentId()).isEqualTo(documentId);
        assertThat(event.metadataJson()).contains("questionHash");
        assertThat(event.metadataJson()).contains("safeLongValue");
        assertThat(event.metadataJson()).doesNotContain("What is the security policy?");
        assertThat(event.metadataJson()).doesNotContain("private source text");
        assertThat(event.metadataJson()).doesNotContain("full retrieved context");
        assertThat(event.metadataJson()).doesNotContain("0.1");
        assertThat(event.metadataJson()).doesNotContain("x".repeat(300));
    }
}
