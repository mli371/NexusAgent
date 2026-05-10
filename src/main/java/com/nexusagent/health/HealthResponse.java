package com.nexusagent.health;

import java.time.Instant;

public record HealthResponse(String status, Instant timestamp) {
}
