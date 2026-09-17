package org.smartledge.ai.model.http;

import org.smartledge.ai.rag.runtime.model.ChatCallOptions;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Immutable transport configuration, supplied by the application composition root. */
public record ModelHttpSettings(Endpoint chat, Endpoint embedding, ChatCallOptions defaults,
                                Map<String, Object> chatExtensions, String thinkingField,
                                int embeddingDimensions, Duration connectTimeout, Duration requestTimeout,
                                Duration readIdleTimeout, int maxResponseBytes, int maxToolArgumentBytes,
                                int chatMaxAttempts, Duration retryBackoff, int workerThreads) {
    private static final Set<String> RESERVED = Set.of("model", "messages", "stream", "stream_options", "tools",
        "tool_choice", "max_tokens", "temperature", "top_p", "reasoning_effort", "verbosity");
    public ModelHttpSettings {
        if (chat == null || embedding == null || defaults == null || embeddingDimensions < 1
            || maxResponseBytes < 1 || maxToolArgumentBytes < 1 || maxToolArgumentBytes > maxResponseBytes
            || chatMaxAttempts < 1 || chatMaxAttempts > 5 || workerThreads < 1 || workerThreads > 32) {
            throw new IllegalArgumentException("Invalid model transport limits");
        }
        positive(connectTimeout); positive(requestTimeout); positive(readIdleTimeout); positive(retryBackoff);
        if (thinkingField == null || !thinkingField.matches("[a-z][a-z0-9_]*") || RESERVED.contains(thinkingField)) {
            throw new IllegalArgumentException("Invalid thinking field mapping");
        }
        chatExtensions = chatExtensions == null ? Map.of() : Map.copyOf(chatExtensions);
        if (chatExtensions.keySet().stream().anyMatch(RESERVED::contains) || chatExtensions.containsKey(thinkingField)) {
            throw new IllegalArgumentException("Extension conflicts with an owned chat parameter");
        }
    }
    private static void positive(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Model timeout/backoff must be positive and at most ten minutes");
        }
    }
    public record Endpoint(URI uri, String apiKey, String model) {
        public Endpoint {
            if (uri == null || !Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                || apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
                throw new IllegalArgumentException("Invalid model endpoint configuration");
            }
        }
        @Override public String toString() { return "Endpoint[model=" + model + "]"; }
    }
}
