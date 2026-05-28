package com.nexusagent.documents.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.NotFoundException;
import com.nexusagent.documents.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DocumentQueryServiceTenantTest {

    @Test
    void getByIdUsesTenantContextAndReturnsNotFoundWhenDocumentIsNotAccessible() {
        DocumentRepository documentRepository = org.mockito.Mockito.mock(DocumentRepository.class);
        DocumentQueryService service = new DocumentQueryService(documentRepository);
        UUID documentId = UUID.randomUUID();
        RequestContext tenantA = new RequestContext("tenant-a", "actor-1");

        when(documentRepository.findById(documentId, tenantA)).thenReturn(Mono.empty());

        StepVerifier.create(service.getById(documentId, tenantA))
                .expectError(NotFoundException.class)
                .verify();

        verify(documentRepository).findById(documentId, tenantA);
    }
}
