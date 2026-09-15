package com.nexusagent.query.api;

import java.util.Map;

public record QueryScopeSummary(String mode, int accessibleDocumentCount, int searchedDocumentCount,
                                int excludedDocumentCount, Map<String, Integer> exclusions) { }
