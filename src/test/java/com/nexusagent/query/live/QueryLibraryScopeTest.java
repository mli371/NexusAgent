package com.nexusagent.query.live;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import java.util.Collections;
import java.util.UUID;
import com.nexusagent.chunking.repository.ChunkRepository;
import com.nexusagent.common.context.RequestContext;
import com.nexusagent.common.error.OperationException;
import com.nexusagent.context.application.ContextProperties;
import com.nexusagent.documents.repository.DocumentRepository;
import com.nexusagent.embeddings.application.EmbeddingService;
import com.nexusagent.embeddings.domain.EmbeddingCoverage;
import com.nexusagent.embeddings.repository.ChildChunkEmbeddingRepository;
import com.nexusagent.query.api.QueryRequest;
import com.nexusagent.retrieval.application.RetrievalProperties;
import org.junit.jupiter.api.Test;

class QueryLibraryScopeTest {
    @Test
    void oversizedLibraryIsRejectedRatherThanSilentlyTruncated() {
        var guard = new LiveQueryGuard(mock(DocumentRepository.class), mock(ChildChunkEmbeddingRepository.class),
                mock(EmbeddingService.class), mock(ChunkRepository.class), mock(QueryLibraryRepository.class));
        var input = LiveQueryInput.from(new QueryRequest("s", "question", null, null, null, false), "trace",
                RequestContext.defaults(), new RetrievalProperties(), new ContextProperties());
        var rows = Collections.nCopies(201, new QueryLibraryRepository.DocumentCoverage(UUID.randomUUID(), new EmbeddingCoverage(1, 1, 1)));
        assertThatThrownBy(() -> guard.selectReady(input, rows)).isInstanceOf(OperationException.class)
                .hasMessageContaining("200 accessible documents");
    }
}
