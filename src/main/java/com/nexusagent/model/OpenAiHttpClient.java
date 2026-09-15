package com.nexusagent.model;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusagent.common.error.OperationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/** No wire logging, raw error bodies, ambient retries, or redirects with credentials. */
public class OpenAiHttpClient {
    private final WebClient client;

    public OpenAiHttpClient(WebClient client) { this.client = client; }

    public Mono<JsonNode> post(String path, Map<String, Object> body, Duration timeout, boolean retryEmbedding) {
        Mono<JsonNode> request = Mono.defer(() -> client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        int status = response.statusCode().value();
                        OperationException failure = new OperationException(
                                status == 429 || status >= 500 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
                                status == 429 ? "MODEL_RATE_LIMITED" : status >= 500 ? "MODEL_UNAVAILABLE" : "MODEL_REQUEST_REJECTED",
                                "Model provider rejected the request; check service configuration or try again later");
                        return response.releaseBody().then(Mono.error(failure));
                    }
                    return response.bodyToMono(JsonNode.class).switchIfEmpty(Mono.error(invalidResponse()));
                }));
        if (retryEmbedding) {
            request = request.retryWhen(Retry.backoff(1, Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(1))
                    .filter(error -> error instanceof OperationException failure
                            && (failure.code().equals("MODEL_RATE_LIMITED") || failure.code().equals("MODEL_UNAVAILABLE")))
                    .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
        }
        return request.timeout(timeout)
                .onErrorMap(TimeoutException.class, error -> new OperationException(
                        HttpStatus.GATEWAY_TIMEOUT, "MODEL_TIMEOUT", "Model request timed out; remote usage may still apply"))
                .onErrorMap(error -> !(error instanceof OperationException), error -> new OperationException(
                        HttpStatus.BAD_GATEWAY, "MODEL_CONNECTION_OR_RESPONSE_ERROR", "Could not read a valid model response"));
    }

    public static OperationException invalidResponse() {
        return OperationException.invalidModelResponse();
    }
}
