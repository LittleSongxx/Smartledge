package org.smartledge.ai.ragtools.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.knowledge.augmentation.config.GraphRagExecutionProperties;
import org.smartledge.ai.ragtools.config.RagToolsProperties;
import org.smartledge.ai.ragtools.model.RagToolsDocumentParseRequest;
import org.smartledge.ai.ragtools.model.RagToolsEmbedRequest;
import org.smartledge.ai.ragtools.model.RagToolsEmbedResponse;
import org.smartledge.ai.ragtools.model.RagToolsDocumentParseResponse;
import org.smartledge.ai.ragtools.model.RagToolsGraphExtractRequest;
import org.smartledge.ai.ragtools.model.RagToolsGraphExtractResponse;
import org.smartledge.ai.ragtools.model.RagToolsHealthResponse;
import org.smartledge.ai.ragtools.model.RagToolsRaptorBuildRequest;
import org.smartledge.ai.ragtools.model.RagToolsRaptorBuildResponse;
import org.smartledge.ai.ragtools.model.RagToolsRerankRequest;
import org.smartledge.ai.ragtools.model.RagToolsRerankResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Transport-only client for the Python rag-tools protocol. */
@Slf4j
@Component
public class RagToolsClient {

    /**
     * 向量化读取超时。
     *
     * <p>刻意不做成数据库配置项：新增 {@code ragTools.*} 键需要一次配置迁移
     * （{@code RagToolsProperties} 对缺失键直接抛异常），而这是传输层常量而非业务开关。
     * 取值只针对首次模型加载与大批量推理留足余量；热调用实测在毫秒级。</p>
     */
    private static final int EMBED_READ_TIMEOUT_MILLIS = 120000;

    private final RestClient restClient;
    private final RestClient documentParseRestClient;
    private final HttpClient graphHttpClient;
    private final Set<CompletableFuture<HttpResponse<byte[]>>> graphCalls = new HashSet<>();
    private boolean graphTransportClosed;
    private final String baseUrl;
    private final int graphMaxResponseBytes;
    private long graphTransportAllowanceMillis = 5000;
    private final RestClient raptorBuildRestClient;
    private final RestClient embedRestClient;
    private final ObjectMapper objectMapper;
    private final int graphReadTimeoutMillis;
    private final int connectTimeoutMillis;
    @Autowired
    public void validateGraphExecutionBudgets(GraphRagExecutionProperties execution) {
        execution.validate();
        graphTransportAllowanceMillis = execution.getReserveMillis();
        if (graphReadTimeoutMillis < execution.getToolBudgetMillis() + execution.getReserveMillis()
                || connectTimeoutMillis <= 0 || connectTimeoutMillis > execution.getReserveMillis()) {
            throw new IllegalArgumentException("GraphRAG HTTP timeouts must cover tool budget and transport allowance");
        }
    }

    public RagToolsClient(RagToolsProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.graphReadTimeoutMillis = properties.getGraphExtractReadTimeoutMs();
        this.connectTimeoutMillis = properties.getConnectTimeoutMs();
        this.baseUrl = properties.getBaseUrl();
        this.graphMaxResponseBytes = properties.getGraphExtractMaxResponseBytes();
        if (graphMaxResponseBytes < 1 || graphMaxResponseBytes > 67108864) {
            throw new IllegalArgumentException("GraphRAG response byte limit must be between 1 and 67108864");
        }
        this.restClient = createRestClient(properties.getBaseUrl(), properties.getConnectTimeoutMs(), 10000);
        this.documentParseRestClient = createRestClient(properties.getBaseUrl(), properties.getConnectTimeoutMs(),
                properties.getDocumentParseReadTimeoutMs());
        this.graphHttpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMillis)))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.raptorBuildRestClient = createRestClient(properties.getBaseUrl(), properties.getConnectTimeoutMs(),
                properties.getRaptorBuildReadTimeoutMs());
        this.embedRestClient = createRestClient(properties.getBaseUrl(), properties.getConnectTimeoutMs(),
                EMBED_READ_TIMEOUT_MILLIS);
    }

    public RagToolsEmbedResponse embed(RagToolsEmbedRequest request) {
        long startedNanos = System.nanoTime();
        RagToolsEmbedResponse response = invoke("/embed",
                () -> post(embedRestClient, "/embed", request, RagToolsEmbedResponse.class));
        log.info("Python 向量化接口调用完成，textCount={}, dimensions={}, model={}, costMillis={}",
                request == null || request.getTexts() == null ? 0 : request.getTexts().size(),
                response == null ? null : response.getDimensions(),
                response == null ? null : response.getModel(),
                elapsedMillis(startedNanos));
        return response;
    }

    public RagToolsHealthResponse health() {
        return invoke("/health", () -> restClient.get().uri("/health")
                .exchange((request, response) -> readResponse("/health", response, RagToolsHealthResponse.class)));
    }

    public RagToolsRerankResponse rerank(RagToolsRerankRequest request) {
        return invoke("/rerank", () -> post(restClient, "/rerank", request, RagToolsRerankResponse.class));
    }

    public RagToolsDocumentParseResponse parseDocument(RagToolsDocumentParseRequest request) {
        long startedNanos = System.nanoTime();
        RagToolsDocumentParseResponse response = invoke("/document/parse",
                () -> post(documentParseRestClient, "/document/parse", request, RagToolsDocumentParseResponse.class));
        log.info(
                "Python 文档解析接口调用完成，fileName={}, fileType={}, parsedTextLength={}, blockCount={}, artifactCount={}, costMillis={}",
                request == null ? null : request.getFileName(), request == null ? null : request.getFileType(),
                response == null || response.getParsedText() == null ? 0 : response.getParsedText().length(),
                response == null || response.getBlocks() == null ? 0 : response.getBlocks().size(),
                response == null || response.getArtifacts() == null ? 0 : response.getArtifacts().size(),
                elapsedMillis(startedNanos));
        return response;
    }

    public RagToolsGraphExtractResponse extractGraph(RagToolsGraphExtractRequest request) {
        if (graphReadTimeoutMillis <= 0 || connectTimeoutMillis <= 0) {
            throw new IllegalArgumentException("GraphRAG HTTP connect/read timeouts must be finite and positive");
        }
        return invoke("/graph/extract", () -> sendGraph(request));
    }

    private RagToolsGraphExtractResponse sendGraph(RagToolsGraphExtractRequest request) {
        CompletableFuture<HttpResponse<byte[]>> call = null;
        try {
            long budget = request == null || request.getBudgetMillis() == null ? graphReadTimeoutMillis
                    : Math.min(graphReadTimeoutMillis, request.getBudgetMillis() + graphTransportAllowanceMillis);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/graph/extract"))
                    .timeout(Duration.ofMillis(Math.max(1, budget))).header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(request))).build();
            synchronized (graphCalls) {
                if (graphTransportClosed) {
                    throw new IllegalStateException("GraphRAG HTTP transport is closed");
                }
                call = graphHttpClient.sendAsync(httpRequest,
                        ignored -> new LimitedBodySubscriber(graphMaxResponseBytes));
                graphCalls.add(call);
            }
            // CompletableFuture covers headers AND the complete body; socket inactivity timeouts do not.
            HttpResponse<byte[]> response = call.get(Math.max(1, budget), TimeUnit.MILLISECONDS);
            String body = new String(response.body(), StandardCharsets.UTF_8);
            MediaType contentType = response.headers().firstValue("Content-Type").map(MediaType::parseMediaType)
                    .orElse(null);
            if (response.statusCode() >= 400) {
                throw new RagToolsTransportException("Python rag-tools 接口返回错误", "/graph/extract", response.statusCode(),
                        contentType, graphErrorPreview(body), null);
            }
            if (body.isBlank()) {
                return null;
            }
            try {
                return objectMapper.readValue(body, RagToolsGraphExtractResponse.class);
            }
            catch (JsonProcessingException e) {
                throw new RagToolsTransportException("Python rag-tools 接口返回非预期 JSON", "/graph/extract",
                        response.statusCode(), contentType, preview(body), e);
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagToolsTransportException("GraphRAG HTTP interrupted", "/graph/extract", null, null, "", e);
        }
        catch (ExecutionException | TimeoutException | JsonProcessingException e) {
            throw new RagToolsTransportException("GraphRAG HTTP failed", "/graph/extract", null, null, "",
                    e instanceof ExecutionException ? e.getCause() : e);
        }
        finally {
            if (call != null) {
                if (!call.isDone()) {
                    call.cancel(true);
                }
                synchronized (graphCalls) {
                    graphCalls.remove(call);
                }
            }
        }
    }

    /** Cancels the subscription before an oversized response can accumulate in memory. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long limit;
        private long received;
        private Flow.Subscription subscription;

        LimitedBodySubscriber(long limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            received += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (received > limit) {
                subscription.cancel();
                delegate.onError(new BufferOverflowException());
            }
            else {
                delegate.onNext(buffers);
            }
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }

    @PreDestroy
    public void closeGraphTransport() {
        synchronized (graphCalls) {
            graphTransportClosed = true;
            for (CompletableFuture<HttpResponse<byte[]>> call : graphCalls) {
                call.cancel(true);
            }
            graphCalls.clear();
        }
    }

    public RagToolsRaptorBuildResponse buildRaptor(RagToolsRaptorBuildRequest request) {
        long startedNanos = System.nanoTime();
        try {
            RagToolsRaptorBuildResponse response = invoke("/raptor/build",
                    () -> post(raptorBuildRestClient, "/raptor/build", request, RagToolsRaptorBuildResponse.class));
            log.info(
                    "Python RAPTOR 构建接口调用完成，documentId={}, taskId={}, chunkCount={}, nodeCount={}, llmSummaryEnabled={}, costMillis={}",
                    request == null ? null : request.getDocumentId(), request == null ? null : request.getTaskId(),
                    request == null || request.getChunks() == null ? 0 : request.getChunks().size(),
                    response == null || response.getNodes() == null ? 0 : response.getNodes().size(),
                    request == null ? null : request.getLlmSummaryEnabled(), elapsedMillis(startedNanos));
            return response;
        }
        catch (RagToolsTransportException exception) {
            log.error("Python RAPTOR 构建接口调用失败，documentId={}, taskId={}, costMillis={}, message={}",
                    request == null ? null : request.getDocumentId(), request == null ? null : request.getTaskId(),
                    elapsedMillis(startedNanos), exception.getMessage(), exception);
            throw exception;
        }
    }

    private <T> T post(RestClient client, String path, Object body, Class<T> responseType) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body(body).exchange((request, response) -> readResponse(path, response, responseType));
    }

    private <T> T invoke(String path, Supplier<T> invocation) {
        try {
            return invocation.get();
        }
        catch (RagToolsTransportException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw new RagToolsTransportException("调用 Python rag-tools 接口失败", path, null, null, "", exception);
        }
    }

    private <T> T readResponse(String path, ClientHttpResponse response, Class<T> responseType) {
        try {
            String body;
            body = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
            MediaType contentType = response.getHeaders().getContentType();
            int status = response.getStatusCode().value();
            if (response.getStatusCode().isError()) {
                throw new RagToolsTransportException("Python rag-tools 接口返回错误", path, status, contentType,
                        preview(body), null);
            }
            if (!StringUtils.hasText(body)) {
                return null;
            }
            try {
                return objectMapper.readValue(body, responseType);
            }
            catch (JsonProcessingException exception) {
                throw new RagToolsTransportException("Python rag-tools 接口返回非预期 JSON", path, status, contentType,
                        preview(body), exception);
            }
        }
        catch (RagToolsTransportException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw new RagToolsTransportException("读取 Python rag-tools 接口响应失败", path, null, null, "", exception);
        }
    }

    private static RestClient createRestClient(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        if (connectTimeoutMs > 0) {
            requestFactory.setConnectTimeout(connectTimeoutMs);
        }
        if (readTimeoutMs > 0) {
            requestFactory.setReadTimeout(readTimeoutMs);
        }
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    private String graphErrorPreview(String body) {
        try {
            JsonNode detail = objectMapper.readTree(body).path("detail");
            if (detail.isArray()) {
                ObjectNode safeBody = objectMapper.createObjectNode();
                ArrayNode errors = safeBody.putArray("detail");
                for (int index = 0; index < Math.min(8, detail.size()); index++) {
                    JsonNode error = detail.get(index);
                    ObjectNode safeError = errors.addObject();
                    String type = error.path("type").asText("");
                    if (type.matches("[A-Za-z0-9_.-]{1,100}")) {
                        safeError.put("type", type);
                    }
                    ArrayNode location = safeError.putArray("loc");
                    JsonNode originalLocation = error.path("loc");
                    if (originalLocation.isArray()) {
                        for (int part = 0; part < Math.min(8, originalLocation.size()); part++) {
                            JsonNode value = originalLocation.get(part);
                            if (value.isIntegralNumber() && value.canConvertToLong()) {
                                location.add(value.longValue());
                            }
                            else if (value.isTextual() && value.asText().matches("[A-Za-z0-9_.-]{1,64}")) {
                                location.add(value.asText());
                            }
                            else {
                                location.add("[redacted]");
                            }
                        }
                    }
                }
                // Preserve valid JSON; raw input/msg/ctx can contain document text.
                return objectMapper.writeValueAsString(safeBody);
            }
        }
        catch (RuntimeException | JsonProcessingException ignored) {
            // Non-JSON errors retain the existing bounded transport preview.
        }
        return preview(body);
    }

    private static String preview(String body) {
        if (body == null) {
            return "";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
