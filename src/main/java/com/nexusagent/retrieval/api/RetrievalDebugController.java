package com.nexusagent.retrieval.api;

import com.nexusagent.retrieval.application.HybridRetrievalService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/retrieval")
public class RetrievalDebugController {

    private final HybridRetrievalService hybridRetrievalService;

    public RetrievalDebugController(HybridRetrievalService hybridRetrievalService) {
        this.hybridRetrievalService = hybridRetrievalService;
    }

    @PostMapping("/debug")
    public Mono<RetrievalDebugResponse> debug(@RequestBody Mono<RetrievalDebugRequest> request) {
        return request
                .flatMap(body -> hybridRetrievalService.retrieve(body.query(), body.documentIds(), body.topK()))
                .map(RetrievalDebugResponse::from);
    }
}
