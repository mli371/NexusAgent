package com.nexusagent.query.api;

public record QueryResolutionDebug(String originalQuestion, String resolvedQuestion, String status, String reasonCode,
                                   int historyTurnsUsed, boolean modelCalled, String resolverModel, String resolverVersion) { }
