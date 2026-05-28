package com.nexusagent.context.application;

import java.util.List;

import com.nexusagent.context.domain.RerankedCandidate;
import com.nexusagent.retrieval.domain.FusedRetrievalCandidate;

public interface Reranker {

    String name();

    List<RerankedCandidate> rerank(String query, List<FusedRetrievalCandidate> candidates);
}
