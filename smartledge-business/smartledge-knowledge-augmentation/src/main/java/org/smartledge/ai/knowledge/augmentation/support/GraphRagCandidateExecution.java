package org.smartledge.ai.knowledge.augmentation.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.knowledge.augmentation.config.GraphRagExecutionProperties;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionRequest;
import org.smartledge.ai.knowledge.augmentation.model.GraphRagExtractionResponse;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagExtractionPort;
import org.smartledge.ai.knowledge.augmentation.port.GraphRagToolException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Freezes and validates tool plans; only complete source coverage can reach the KG writer. */
@Slf4j
public final class GraphRagCandidateExecution {
    public static final String VERSION = "graph-candidates.v3";
    private final GraphRagExtractionPort port;
    private final ObjectMapper mapper;
    private final GraphRagBatchExecutor executor;
    private final GraphRagExecutionProperties limits;

    public GraphRagCandidateExecution(GraphRagExtractionPort port, ObjectMapper mapper,
            GraphRagBatchExecutor executor) {
        this.port = port;
        this.mapper = mapper;
        this.executor = executor;
        this.limits = executor.limits();
    }

    public GraphRagExtractionResponse execute(GraphRagExtractionRequest source, Runnable checkLease,
            Consumer<Map<String, Object>> progress) {
        return new Session(source, checkLease, progress).run();
    }

    private final class Session {
        private final GraphRagExtractionRequest input;
        private final Runnable checkLease;
        private final Consumer<Map<String, Object>> progress;
        private final long started = executor.nanoTime();
        private final long deadline = started + limits.getDocumentBudgetMillis() * 1_000_000;
        private long heartbeat;
        private final Set<Long> completed = new LinkedHashSet<>();
        private final Set<Long> failed = new LinkedHashSet<>();
        private final Map<Long, RuntimeException> sourceFailures = new LinkedHashMap<>();
        private final Map<Long, Integer> remaining = new LinkedHashMap<>();
        private final Map<String, Object> observation = new LinkedHashMap<>();
        private final List<Map<String, Object>> events = new ArrayList<>();
        private final Map<String, GraphRagExtractionResponse> accepted = new TreeMap<>();
        private final List<Work> waiting = new ArrayList<>();
        private final List<Work> active = new ArrayList<>();
        private int planned;
        private int succeeded;
        private int failedBatches;
        /** 完成但一个候选都没产出的批次数：结构合规时的"少抽"必须可观测，不能是静默降级。 */
        private int emptyBatches;
        private int scheduledRetries;
        private int splits;
        private int timeoutSplits;
        private final Map<String, Integer> timeoutSplitsByRoot = new LinkedHashMap<>();
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicInteger batchAttempts = new AtomicInteger();
        private final AtomicInteger retries = new AtomicInteger();
        private long eventCount;
        private long candidateBytes;
        private final GraphRagBatchCandidateValidation.Rejections rejections =
                new GraphRagBatchCandidateValidation.Rejections();
        private GraphRagExtractionResponse planResponse;
        private RuntimeException lastFailure;

        Session(GraphRagExtractionRequest source, Runnable checkLease, Consumer<Map<String, Object>> progress) {
            input = copy(source);
            this.checkLease = checkLease;
            this.progress = progress;
            observation.put("documentId", input.getDocumentId());
            observation.put("taskId", input.getTaskId());
            observation.put("sourceParseTaskId", input.getSourceParseTaskId());
            if (input.getPlanId() != null) {
                observation.put("planId", input.getPlanId());
            }
            observation.put("excludedBlankChunkIds", input.getExcludedBlankChunkIds());
            observation.put("execution", mapper.convertValue(limits, Map.class));
            observation.put("batchObservations", events);
        }

        GraphRagExtractionResponse run() {
            try {
                long maxDocumentBytes = requestMaxDocumentBytes();
                if (input.getChunks().stream().mapToLong(c -> c.getText().getBytes(StandardCharsets.UTF_8).length)
                        .sum() > maxDocumentBytes) {
                    throw invalid("DOCUMENT_RESOURCE_LIMIT");
                }
                if (input.getInputFingerprint() == null) {
                    input.setInputFingerprint(hash(mapper.writeValueAsString(input)));
                }
                input.setOperation("plan");
                waiting.add(new Work(copy(input), 0, null, null, 0));
                drain();
                Map<String, Object> plan = metadata(planResponse);
                match(plan, "schemaVersion", VERSION);
                match(plan, "operation", "plan");
                match(plan, "inputFingerprint", input.getInputFingerprint());
                if (!emptyCandidates(planResponse)) {
                    throw invalid("PLAN_CONTAINS_CANDIDATES");
                }
                match(plan, "offsetUnit", "unicode-code-point");
                input.setConfigurationFingerprint(fingerprint(plan.get("configurationFingerprint")));
                input.setPlanFingerprint(fingerprint(plan.get("planFingerprint")));
                List<Map<String, Object>> batches = maps(plan.get("batches"));
                validatePlan(input, batches, plan);
                planned = batches.size();
                observation.put("originalPlannedBatchCount", planned);
                observation.put("inputFingerprint", input.getInputFingerprint());
                observation.put("configurationFingerprint", input.getConfigurationFingerprint());
                observation.put("planFingerprint", input.getPlanFingerprint());
                observation.put("inference", plan.get("inference"));
                for (Map<String, Object> batch : batches) {
                    GraphRagExtractionRequest request = copy(input);
                    request.setOperation("extract");
                    request.setBatchId(string(batch.get("batchId")));
                    request.setSegments(maps(batch.get("segments")));
                    Set<Long> ids = chunkIds(request.getSegments());
                    request.setChunks(input.getChunks().stream().filter(c -> ids.contains(c.getChunkId())).toList());
                    ids.forEach(id -> remaining.merge(id, 1, Integer::sum));
                    waiting.add(new Work(request, 0, null, request.getBatchId(), number(batch.get("promptTokensEstimate"))));
                }
                publish("running");
                drain();
                guard();
                if (completed.size() != input.getChunks().size()) {
                    observation.put("status", "partial");
                    RuntimeException unresolvedFailure = unresolvedFailure();
                    throw new CandidateExecutionException("NO_COMMIT: "
                            + (unresolvedFailure == null ? "INCOMPLETE_COVERAGE" : unresolvedFailure.getMessage()),
                            unresolvedFailure,
                            snapshot("partial"));
                }
                GraphRagExtractionResponse merged = new GraphRagExtractionResponse();
                accepted.forEach((id, response) -> {
                    namespace(response, id);
                    merged.getEntities().addAll(response.getEntities());
                    merged.getRelations().addAll(response.getRelations());
                    merged.getEvidences().addAll(response.getEvidences());
                });
                observation.put("schemaVersion", VERSION);
                observation.put("candidateEntityCount", merged.getEntities().size());
                observation.put("candidateRelationCount", merged.getRelations().size());
                observation.put("candidateEvidenceCount", merged.getEvidences().size());
                publish("completed");
                merged.setMetadata(snapshot("completed"));
                return merged;
            }
            catch (Exception failure) {
                if (failure instanceof GraphRagToolException tool) {
                    observation.put("error", tool.diagnostics());
                }
                else if (failure instanceof GraphRagBuildStoppedException stopped) {
                    observation.put("error", Map.of("category", stopped.reason().name(), "layer", "JAVA"));
                }
                else {
                    observation.put("error",
                            Map.of("category", "CONTRACT_OR_LEASE", "causeType", failure.getClass().getSimpleName()));
                }
                for (Work work : active) {
                    executor.cancel(work.future);
                    event(work, "cancelled", null);
                }
                active.clear();
                Map<String, Object> finalState = snapshot("failed");
                if (!(failure instanceof GraphRagBuildStoppedException)) {
                    try {
                        checkLease.run();
                        progress.accept(finalState);
                    }
                    catch (RuntimeException ignored) {
                        /* never project after ownership loss */
                    }
                }
                throw new CandidateExecutionException("NO_COMMIT: " + failure.getMessage(), failure, finalState);
            }
            finally {
                active.forEach(work -> executor.cancel(work.future));
                accepted.clear();
            }
        }

        private long requestMaxDocumentBytes() {
            if (input.getOptions() == null) {
                throw invalid("MISSING_CONFIGURATION:maxDocumentBytes");
            }
            Object value = input.getOptions().get("maxDocumentBytes");
            if (!(value instanceof Number number)) {
                throw invalid("INVALID_CONFIGURATION:maxDocumentBytes");
            }
            long limit = number.longValue();
            if (limit < 1 || limit > 64000000L) {
                throw invalid("INVALID_CONFIGURATION:maxDocumentBytes");
            }
            return limit;
        }

        void drain() throws Exception {
            while (!waiting.isEmpty() || !active.isEmpty()) {
                guard();
                long now = executor.nanoTime();
                for (Iterator<Work> iterator = active.iterator(); iterator.hasNext();) {
                    Work work = iterator.next();
                    if (!work.future.isDone()
                            && (work.startedNanos == 0 || now - work.startedNanos < (work.request.getBudgetMillis()
                                    + limits.getReserveMillis()) * 1_000_000)) {
                        continue;
                    }
                    iterator.remove();
                    RuntimeException failure = null;
                    GraphRagExtractionResponse response = null;
                    try {
                        if (!work.future.isDone()) {
                            executor.cancel(work.future);
                            throw timeoutTool(work, work.request.getBudgetMillis());
                        }
                        response = work.future.get();
                        guard();
                        if (work.finishedNanos - work.startedNanos > (work.request.getBudgetMillis()
                                + limits.getReserveMillis()) * 1_000_000) {
                            throw timeoutTool(work, work.request.getBudgetMillis());
                        }
                    }
                    catch (ExecutionException e) {
                        failure = e.getCause() instanceof RuntimeException runtime ? runtime
                                : new IllegalStateException("GraphRAG worker failed", e.getCause());
                    }
                    catch (RuntimeException e) {
                        failure = e;
                    }
                    if (failure == null) {
                        try {
                            if ("plan".equals(work.request.getOperation())) {
                                planResponse = response;
                            }
                            else {
                                boolean candidatesEmpty = validateBatch(work.request, response);
                                long bytes = mapper.writeValueAsBytes(response).length;
                                if (candidateBytes + bytes > limits.getMaxCandidateBytes()) {
                                    throw invalid("CANDIDATE_MEMORY_LIMIT");
                                }
                                candidateBytes += bytes;
                                rejections.merge(response.getMetadata(), work.request.getBatchId());
                                accepted.put(work.request.getBatchId(), response);
                                succeeded++;
                                if (candidatesEmpty) {
                                    emptyBatches++;
                                    log.warn("GraphRAG 抽取批次未产出任何候选：结构合规但内容为空，请核对抽取质量: "
                                                    + "batchId={}, attempt={}, sourceIds={}, promptTokensEstimate={}",
                                            work.request.getBatchId(), work.attempt,
                                            work.request.getSegments().stream()
                                                    .map(segment -> string(segment.get("sourceId"))).toList(),
                                            response.getMetadata() == null
                                                    ? null : response.getMetadata().get("promptTokensEstimate"));
                                }
                                for (long id : chunkIds(work.request.getSegments())) {
                                    if (!failed.contains(id)) {
                                        sourceFailures.remove(id);
                                    }
                                    if (!failed.contains(id)
                                            && remaining.compute(id, (key, value) -> value - 1) == 0) {
                                        completed.add(id);
                                    }
                                }
                            }
                            event(work, "completed", response);
                        }
                        catch (RuntimeException e) {
                            failure = e;
                        }
                    }
                    if (failure != null) {
                        onFailure(work, failure);
                    }
                    publish("running");
                }
                for (Iterator<Work> iterator = waiting.iterator(); iterator.hasNext()
                        && active.size() < limits.getDocumentConcurrency();) {
                    Work work = iterator.next();
                    if (work.readyAt > executor.nanoTime()) {
                        continue;
                    }
                    try {
                        work.startedNanos = 0;
                        work.finishedNanos = 0;
                        work.submittedNanos = executor.nanoTime();
                        work.attempt++;
                        int attemptNumber = work.attempt;
                        work.error = null;
                        work.future = executor.submit(() -> {
                            long available = (deadline - executor.nanoTime()) / 1_000_000
                                    - limits.getReserveMillis();
                            if (available <= 0) {
                                throw documentTimeoutTool((executor.nanoTime() - started) / 1_000_000);
                            }
                            work.request.setBudgetMillis(Math.min(limits.getToolBudgetMillis(), available));
                            work.startedNanos = executor.nanoTime();
                            invocations.incrementAndGet();
                            if ("extract".equals(work.request.getOperation())) {
                                batchAttempts.incrementAndGet();
                            }
                            if (attemptNumber > 1) {
                                retries.incrementAndGet();
                            }
                            try {
                                return invoke(copy(work.request));
                            }
                            finally {
                                work.finishedNanos = executor.nanoTime();
                            }
                        });
                        observation.put("waitingForCapacity", false);
                        active.add(work);
                        iterator.remove();
                        event(work, "submitted", null);
                        publish("running");
                    }
                    catch (RejectedExecutionException saturated) {
                        work.attempt--;
                        observation.put("waitingForCapacity", true);
                        break; // bounded global queue; no extra Future is retained
                    }
                }
                if (!waiting.isEmpty() || !active.isEmpty()) {
                    try {
                        Thread.sleep(Math.min(50L,
                                Math.max(1, (deadline - executor.nanoTime()) / 1_000_000)));
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new GraphRagBuildStoppedException(GraphRagBuildStoppedException.Reason.CANCELLED);
                    }
                }
            }
        }

        void onFailure(Work work, RuntimeException failure) {
            if (failure instanceof GraphRagBuildStoppedException stopped) {
                throw stopped;
            }
            guard();
            lastFailure = failure;
            for (long id : chunkIds(work.request.getSegments())) {
                sourceFailures.put(id, failure);
            }
            if (failure instanceof GraphRagToolException tool) {
                work.error = tool.diagnostics();
                event(work, "failed", null);
                boolean retryable = switch (tool.category()) {
                case CONNECTION, CONNECT_TIMEOUT, READ_TIMEOUT, RATE_LIMIT, UPSTREAM_SERVER -> true;
                default -> false;
                };
                if (retryable && work.attempt < limits.getMaxBatchAttempts()) {
                    long delay = limits.getRetryBackoffMillis();
                    delay = Math.max(delay, tool.retryAfterMillis());
                    long available = (deadline - executor.nanoTime()) / 1_000_000 - limits.getReserveMillis();
                    if (delay < available) {
                        scheduledRetries++;
                        // A cancelled provider may still finish: never let it mutate a later attempt's timing.
                        Work retry = new Work(copy(work.request), work.depth, work.parent, work.root, work.estimate);
                        retry.attempt = work.attempt;
                        retry.error = work.error;
                        retry.submittedNanos = work.submittedNanos;
                        retry.startedNanos = work.startedNanos;
                        retry.readyAt = executor.nanoTime() + delay * 1_000_000;
                        waiting.add(retry);
                        event(retry, "retry_wait", null);
                        return;
                    }
                }
                boolean responseResourceExceeded = tool.category() == GraphRagToolException.Category.RESOURCE_LIMIT
                        && isResponseResource(tool);
                boolean volumeOverflow = tool.category() == GraphRagToolException.Category.OUTPUT_TRUNCATED
                        || tool.category() == GraphRagToolException.Category.CONTEXT_LIMIT
                        || responseResourceExceeded;
                boolean timeoutOverflow = tool.category() == GraphRagToolException.Category.READ_TIMEOUT;
                boolean timeoutSplitAllowed = timeoutOverflow && work.root != null
                        && timeoutSplitsByRoot.getOrDefault(work.root, 0) < limits.getMaxTimeoutSplitsPerSource();
                if ((volumeOverflow || timeoutSplitAllowed)
                        && !"plan".equals(work.request.getOperation()) && work.depth < limits.getMaxSplitDepth()
                        && splits < limits.getMaxSplits() && planned < limits.getMaxBatches()) {
                    List<Work> children = split(work);
                    if (!children.isEmpty()) {
                        splits++;
                        planned++;
                        if (timeoutOverflow) {
                            timeoutSplitsByRoot.merge(work.root, 1, Integer::sum);
                            timeoutSplits++;
                        }
                        for (long id : chunkIds(work.request.getSegments())) {
                            remaining.merge(id, -1, Integer::sum);
                        }
                        for (Work child : children) {
                            for (long id : chunkIds(child.request.getSegments())) {
                                remaining.merge(id, 1, Integer::sum);
                            }
                        }
                        waiting.addAll(children);
                        event(work, "split", null);
                        return;
                    }
                }
            }
            else {
                work.error = Map.of("category", "PROTOCOL", "causeType", failure.getClass().getSimpleName());
                event(work, "failed", null);
            }
            if (!"plan".equals(work.request.getOperation())) {
                failedBatches++;
                failed.addAll(chunkIds(work.request.getSegments()));
                failed.removeAll(completed);
                for (long id : chunkIds(work.request.getSegments())) {
                    completed.remove(id);
                }
            }
            if ("plan".equals(work.request.getOperation())) {
                throw failure;
            }
            // Isolate only this source set; unrelated queued and running batches continue.
            return;
        }

        private RuntimeException unresolvedFailure() {
            for (long id : input.getChunks().stream().map(c -> c.getChunkId()).toList()) {
                if (!completed.contains(id)) {
                    RuntimeException failure = sourceFailures.get(id);
                    if (failure != null) {
                        return failure;
                    }
                }
            }
            return lastFailure;
        }

        private boolean isResponseResource(GraphRagToolException tool) {
            Object resource = tool.diagnostics().get("resource");
            return Objects.equals(resource, "responseBytes") || Objects.equals(resource, "response_bytes")
                    || Objects.equals(resource, "outputTokens") || Objects.equals(resource, "output_tokens");
        }

        List<Work> split(Work parent) {
            List<Map<String, Object>> segments = parent.request.getSegments();
            List<List<Map<String, Object>>> partitions;
            if (segments.size() > 1) {
                int middle = segments.size() / 2;
                partitions = List.of(segments.subList(0, middle), segments.subList(middle, segments.size()));
            }
            else {
                Map<String, Object> segment = segments.get(0);
                String text = string(segment.get("text"));
                int length = text.codePointCount(0, text.length());
                if (length < 2) {
                    return List.of();
                }
                int middle = length / 2, offset = text.offsetByCodePoints(0, middle);
                LinkedHashMap<String, Object> left = new LinkedHashMap<>(segment);
                LinkedHashMap<String, Object> right = new LinkedHashMap<>(segment);
                left.put("end", number(segment.get("start")) + middle);
                left.put("text", text.substring(0, offset));
                left.put("contentFingerprint", hash((String) left.get("text")));
                left.put("sourceId", string(segment.get("sourceId")) + ".0");
                right.put("start", number(segment.get("start")) + middle);
                right.put("text", text.substring(offset));
                right.put("contentFingerprint", hash((String) right.get("text")));
                right.put("sourceId", string(segment.get("sourceId")) + ".1");
                partitions = List.of(List.of(left), List.of(right));
            }
            List<Work> children = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                GraphRagExtractionRequest request = copy(parent.request);
                request.setBatchId(parent.request.getBatchId() + "." + index);
                request.setSegments(partitions.get(index));
                Set<Long> ids = chunkIds(request.getSegments());
                request.setChunks(input.getChunks().stream().filter(c -> ids.contains(c.getChunkId())).toList());
                children.add(new Work(request, parent.depth + 1, parent.request.getBatchId(), parent.root, 0));
            }
            return children;
        }

        void guard() {
            if (Thread.currentThread().isInterrupted()) {
                throw new GraphRagBuildStoppedException(GraphRagBuildStoppedException.Reason.CANCELLED);
            }
            if (executor.nanoTime() >= deadline - limits.getReserveMillis() * 1_000_000) {
                throw documentTimeoutTool((executor.nanoTime() - started) / 1_000_000);
            }
            if (executor.nanoTime() >= heartbeat) {
                checkLease.run();
                heartbeat = executor.nanoTime() + 10L * 1_000_000;
                if (planned > 0) {
                    publish("running");
                }
            }
        }

        void event(Work work, String status, GraphRagExtractionResponse response) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("batchId", work.request.getBatchId() == null ? "plan" : work.request.getBatchId());
            event.put("attempt", work.attempt);
            event.put("status", status);
            long rejected = response != null && response.getMetadata() != null
                    && response.getMetadata().get("candidateRejectionCount") instanceof Number count
                            ? count.longValue() : 0;
            String disposition = status;
            if ("completed".equals(status) && rejected > 0) {
                disposition = "candidate_rejected";
            }
            else if ("failed".equals(status)) {
                disposition = "source_failed";
            }
            else if ("cancelled".equals(status)) {
                disposition = "source_unprocessed";
            }
            event.put("disposition", disposition);
            if ("completed".equals(status)) {
                event.put("candidateRejectionCount", rejected);
            }
            event.put("depth", work.depth);
            event.put("splitDepth", work.depth);
            event.put("splitAttempts", work.splitAttempts);
            event.put("toolBudgetMillis", work.request.getBudgetMillis());
            if (work.parent != null) {
                event.put("parentBatchId", work.parent);
            }
            event.put("costMillis", Math.max(0, (executor.nanoTime() - work.submittedNanos) / 1_000_000));
            if (work.startedNanos > 0) {
                event.put("queueMillis", (work.startedNanos - work.submittedNanos) / 1_000_000);
            }
            event.put("promptTokensEstimate", response == null || response.getMetadata() == null ? work.estimate
                    : response.getMetadata().getOrDefault("promptTokensEstimate", work.estimate));
            event.put("sourceChunkIds", chunkIds(work.request.getSegments()));
            event.put("sourceIds", work.request.getSegments().stream().map(segment -> string(segment.get("sourceId")))
                    .toList());
            Object usage = response == null || response.getMetadata() == null
                    ? work.error == null ? null : work.error.get("usage")
                    : response.getMetadata().get("usage");
            event.put("usage", usage == null ? "NOT_REPORTED" : usage);
            if ("retry_wait".equals(status)) {
                event.put("backoffMillis", Math.max(0, (work.readyAt - executor.nanoTime()) / 1_000_000));
            }
            if (work.error != null && !"completed".equals(status)) {
                event.put("error", work.error);
                Object resource = work.error.get("resource");
                if (resource != null) {
                    event.put("resource", resource);
                    Object actual = work.error.get("actualLength");
                    if (actual == null) {
                        actual = work.error.get("actualCount");
                    }
                    if (actual == null) {
                        actual = work.error.get("actual");
                    }
                    event.put("actual", actual);
                    event.put("boundary", work.error.get("configuredLimit"));
                }
            }
            events.add(event);
            event.put("sequence", ++eventCount);
            observation.put("lastEvent", event);
        }

        Map<String, Object> snapshot(String status) {
            counts(observation, input, completed, failed, planned, succeeded);
            observation.put("emptyBatchCount", emptyBatches);
            observation.put("emptyBatchRatio", emptyBatchRatio());
            observation.put("status", status);
            observation.put("failedBatchCount", failedBatches);
            observation.put("unprocessedBatchCount", Math.max(0, planned - succeeded - failedBatches));
            observation.put("inFlightBatchCount", active.size());
            observation.put("activeBatches",
                    active.stream()
                            .map(work -> Map.of("batchId",
                                    work.request.getBatchId() == null ? "plan" : work.request.getBatchId(), "attempt",
                                    work.attempt, "state", work.startedNanos == 0 ? "queued" : "running"))
                            .toList());
            observation.put("observationEventCount", eventCount);
            observation.put("retryCount", retries.get());
            observation.put("scheduledRetryCount", scheduledRetries);
            observation.put("toolInvocationCount", invocations.get());
            observation.put("batchAttemptCount", batchAttempts.get());
            observation.put("splitCount", splits);
            observation.put("timeoutSplitCount", timeoutSplits);
            observation.put("candidateBytes", candidateBytes);
            rejections.write(observation);
            observation.put("costMillis", (executor.nanoTime() - started) / 1_000_000);
            observation.put("remainingBudgetMillis", Math.max(0, (deadline - executor.nanoTime()) / 1_000_000));
            return boundObservation(observation, limits.getMaxObservationBytes(), mapper);
        }

        /** 已产出批次里空产出的占比：分母用完成批次数，1.0 表示所有完成批次都什么都没抽到。 */
        private double emptyBatchRatio() {
            return succeeded == 0 ? 0.0 : Math.round(emptyBatches * 10000.0 / succeeded) / 10000.0;
        }

        void publish(String status) {
            progress.accept(snapshot(status));
        }
    }

    private static final class Work {
        final GraphRagExtractionRequest request;
        final int depth;
        final String parent;
        /** Root batch ID of the original (pre-split) batch; null for plan, own batchId for top-level extract. */
        final String root;
        final long estimate;
        int attempt;
        int splitAttempts;
        long readyAt, submittedNanos;
        volatile long startedNanos, finishedNanos;
        Map<String, Object> error;
        FutureTask<GraphRagExtractionResponse> future;

        Work(GraphRagExtractionRequest request, int depth, String parent, String root, long estimate) {
            this.request = request;
            this.depth = depth;
            this.parent = parent;
            this.root = root;
            this.estimate = estimate;
            this.splitAttempts = depth;
        }
    }

    private static GraphRagToolException tool(GraphRagToolException.Category category, Work work, String layer) {
        return new GraphRagToolException(category, Map.of("batchId",
                work == null || work.request.getBatchId() == null ? "plan" : work.request.getBatchId(), "layer", layer),
                null);
    }

    private GraphRagToolException documentTimeoutTool(long actualMillis) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("batchId", "plan");
        diagnostics.put("layer", "JAVA");
        diagnostics.put("resource", "documentTime");
        diagnostics.put("actual", actualMillis);
        diagnostics.put("boundary", limits.getDocumentBudgetMillis());
        diagnostics.put("configuredLimit", limits.getDocumentBudgetMillis());
        return new GraphRagToolException(GraphRagToolException.Category.DOCUMENT_TIMEOUT, diagnostics, null);
    }

    private GraphRagToolException timeoutTool(Work work, long budgetMillis) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("batchId", work == null || work.request.getBatchId() == null ? "plan" : work.request.getBatchId());
        diagnostics.put("layer", "JAVA_PYTHON");
        diagnostics.put("resource", "toolTime");
        long actualMillis = work != null && work.startedNanos > 0
                ? Math.max(0, (executor.nanoTime() - work.startedNanos) / 1_000_000) : budgetMillis;
        diagnostics.put("actual", actualMillis);
        diagnostics.put("boundary", budgetMillis);
        diagnostics.put("configuredLimit", budgetMillis);
        return new GraphRagToolException(GraphRagToolException.Category.READ_TIMEOUT, diagnostics, null);
    }

    private void validatePlan(GraphRagExtractionRequest input, List<Map<String, Object>> batches,
            Map<String, Object> plan) {
        if (batches.isEmpty() || batches.size() > limits.getMaxBatches()) {
            throw invalid("PLAN_RESOURCE_LIMIT");
        }
        Map<String, Object> inference = map(plan.get("inference"));
        if (!equivalentOptions(inference.get("options"), input.getOptions())) {
            throw invalid("CONFIGURATION_OPTIONS_DRIFT");
        }
        match(inference, "version", VERSION);
        match(inference, "tokenizer", "tokenizers.byte-level-unmerged.estimate.v1");
        fingerprint(inference.get("promptFingerprint"));
        fingerprint(inference.get("endpointFingerprint"));
        string(inference.get("model"));
        long context = number(inference.get("contextTokens"));
        long reserve = number(inference.get("outputReserve"));
        long margin = number(inference.get("promptReserve"));
        if (context <= 0 || reserve <= 0 || margin <= 0 || reserve >= context || margin >= context - reserve) {
            throw invalid("TOKEN_RESERVE_INVALID");
        }
        Map<Long, GraphRagExtractionRequest.Chunk> chunks = new LinkedHashMap<>();
        input.getChunks().forEach(c -> {
            if (chunks.put(c.getChunkId(), c) != null) {
                throw invalid("DUPLICATE_CHUNK");
            }
        });
        Map<Long, List<long[]>> ranges = new LinkedHashMap<>();
        Set<String> batchIds = new HashSet<>(), sourceIds = new HashSet<>();
        for (Map<String, Object> batch : batches) {
            String batchId = string(batch.get("batchId"));
            if (!batchId.matches("b[1-9][0-9]*") || !batchIds.add(batchId)) {
                throw invalid("BATCH_ID_INVALID");
            }
            long cost = number(batch.get("promptTokensEstimate"));
            if (cost <= 0 || cost > number(input.getOptions().get("inputTokenBudget"))
                    || cost > context - reserve - margin) {
                throw invalid("PROMPT_BUDGET_INVALID");
            }
            List<Map<String, Object>> segments = maps(batch.get("segments"));
            if (segments.isEmpty() || segments.size() > number(input.getOptions().get("batchChunkLimit"))) {
                throw invalid("BATCH_SIZE_INVALID");
            }
            for (Map<String, Object> segment : segments) {
                if (!sourceIds.add(string(segment.get("sourceId")))) {
                    throw invalid("DUPLICATE_SOURCE");
                }
                long id = number(segment.get("chunkId")), start = number(segment.get("start")),
                        end = number(segment.get("end"));
                GraphRagExtractionRequest.Chunk chunk = chunks.get(id);
                if (chunk == null || start < 0 || end <= start
                        || end > chunk.getText().codePointCount(0, chunk.getText().length())) {
                    throw invalid("SOURCE_RANGE_INVALID");
                }
                String original = chunk.getText().substring(chunk.getText().offsetByCodePoints(0, (int) start),
                        chunk.getText().offsetByCodePoints(0, (int) end));
                if (!Objects.equals(original, segment.get("text"))
                        || !Objects.equals(hash(original), segment.get("contentFingerprint"))) {
                    throw invalid("SOURCE_CONTENT_INVALID");
                }
                ranges.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new long[] { start, end });
            }
        }
        if (!ranges.keySet().equals(chunks.keySet())) {
            throw invalid("SOURCE_SET_MISMATCH");
        }
        for (Map.Entry<Long, List<long[]>> entry : ranges.entrySet()) {
            long covered = 0;
            entry.getValue().sort(Comparator.comparingLong(r -> r[0]));
            for (long[] range : entry.getValue()) {
                // v2 first release uses adjacent partitions, so duplicate/overlapping work fails explicitly.
                if (range[0] != covered) {
                    throw invalid("SOURCE_COVERAGE_GAP_OR_OVERLAP");
                }
                covered = range[1];
            }
            String original = chunks.get(entry.getKey()).getText();
            if (covered != original.codePointCount(0, original.length())) {
                throw invalid("SOURCE_TAIL_MISSING");
            }
        }
    }

    /**
     * 校验一个抽取批次，并回答"模型这一批是不是一个候选都没给"。
     *
     * <p>空产出必须在**批量拒绝过滤之前**判定：候选全部被拒绝是独立信号（{@code candidateRejectionCount}
     * 与拒绝原因都会进观测），不能被算成"什么都没抽到"，否则两者混在一起就分不清"模型没抽"和"抽了但不合规"。</p>
     */
    private boolean validateBatch(GraphRagExtractionRequest request, GraphRagExtractionResponse response) {
        Map<String, Object> meta = metadata(response);
        match(meta, "schemaVersion", VERSION);
        match(meta, "operation", "extract");
        match(meta, "status", "completed");
        match(meta, "inputFingerprint", request.getInputFingerprint());
        match(meta, "configurationFingerprint", request.getConfigurationFingerprint());
        match(meta, "planFingerprint", request.getPlanFingerprint());
        match(meta, "batchId", request.getBatchId());
        List<String> sourceIds = request.getSegments().stream().map(s -> string(s.get("sourceId"))).toList();
        if (!sourceIds.equals(meta.get("completedSourceIds"))) {
            throw invalid("BATCH_COVERAGE_MISMATCH");
        }
        if (response.getEntities() == null || response.getRelations() == null || response.getEvidences() == null
                || response.getCommunities() == null || !response.getCommunities().isEmpty()) {
            throw invalid("CANDIDATE_SHAPE_INVALID");
        }
        boolean candidatesEmpty = emptyCandidates(response);
        GraphRagBatchCandidateValidation.filter(request, response);
        return candidatesEmpty;
    }

    private void namespace(GraphRagExtractionResponse response, String batchId) {
        String prefix = batchId + ":";
        response.getEntities().forEach(e -> {
            e.setId(prefix + e.getId());
            e.setEvidenceIds(prefix(e.getEvidenceIds(), prefix));
        });
        response.getRelations().forEach(r -> {
            r.setId(prefix + r.getId());
            r.setSourceEntityId(prefixId(r.getSourceEntityId(), prefix));
            r.setTargetEntityId(prefixId(r.getTargetEntityId(), prefix));
            r.setEvidenceIds(prefix(r.getEvidenceIds(), prefix));
        });
        response.getEvidences().forEach(e -> {
            e.setId(prefix + e.getId());
            if (e.getEntityId() != null && !e.getEntityId().isBlank()) {
                e.setEntityId(prefix + e.getEntityId());
            }
            if (e.getRelationId() != null && !e.getRelationId().isBlank()) {
                e.setRelationId(prefix + e.getRelationId());
            }
        });
    }

    private String prefixId(String id, String prefix) {
        return id == null || id.isBlank() ? id : prefix + id;
    }

    private List<String> prefix(List<String> values, String prefix) {
        return values == null ? List.of() : values.stream().map(v -> prefix + v).toList();
    }

    private GraphRagExtractionResponse invoke(GraphRagExtractionRequest request) {
        try {
            return port.extract(request);
        }
        catch (RuntimeException exception) {
            if (exception instanceof GraphRagToolException tool) {
                throw tool;
            }
            throw new GraphRagToolException(GraphRagToolException.Category.UNKNOWN,
                    Map.of("batchId", request.getBatchId() == null ? "plan" : request.getBatchId(), "causeType",
                            exception.getClass().getSimpleName()),
                    exception);
        }
    }

    private GraphRagExtractionRequest copy(GraphRagExtractionRequest input) {
        return mapper.convertValue(input, GraphRagExtractionRequest.class);
    }

    /** Samples diagnostic lists only; authoritative coverage sets and candidate buffers are untouched. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> boundObservation(Map<String, Object> metadata, int maxBytes,
            ObjectMapper mapper) {
        Map<String, Object> result = mapper.convertValue(metadata, Map.class);
        try {
            while (mapper.writeValueAsBytes(result).length > maxBytes) {
                if (result.get("batchObservations") instanceof List<?> events && events.size() > 1) {
                    result.put("batchObservations", new ArrayList<>(events.subList(events.size() / 2, events.size())));
                    result.put("batchObservationHistoryTruncated", true);
                    continue;
                }
                List<DiagnosticList> lists = new ArrayList<>();
                collectDiagnosticLists(result, lists);
                DiagnosticList largest = lists.stream()
                        .filter(item -> !item.values.isEmpty()).max(Comparator.comparingInt(item -> item.values.size()))
                        .orElse(null);
                if (largest == null) {
                    throw invalid("OBSERVATION_RESOURCE_LIMIT");
                }
                largest.owner.put(largest.key, new ArrayList<>(largest.values.subList(0, largest.values.size() / 2)));
                result.put("observationListsTruncated", true);
                if (largest.key.endsWith("SourceIds") || largest.key.equals("excludedBlankChunkIds")) {
                    result.put("sourceIdListsTruncated", true);
                }
            }
            return result;
        }
        catch (JsonProcessingException failure) {
            throw invalid("OBSERVATION_SERIALIZATION_FAILED");
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectDiagnosticLists(Map<String, Object> owner, List<DiagnosticList> out) {
        owner.forEach((key, value) -> {
            if (value instanceof List<?> list) {
                out.add(new DiagnosticList(owner, key, list));
            }
            else if (value instanceof Map<?, ?> nested) {
                collectDiagnosticLists((Map<String, Object>) nested, out);
            }
        });
    }

    private record DiagnosticList(Map<String, Object> owner, String key, List<?> values) {
    }

    private static void counts(Map<String, Object> out, GraphRagExtractionRequest input, Set<Long> completed,
            Set<Long> failed, int plannedBatches, int successfulBatches) {
        Set<Long> unprocessed = new LinkedHashSet<>();
        input.getChunks().forEach(c -> unprocessed.add(c.getChunkId()));
        unprocessed.removeAll(completed);
        unprocessed.removeAll(failed);
        out.put("plannedSourceCount", input.getChunks().size());
        out.put("completedSourceCount", completed.size());
        out.put("failedSourceCount", failed.size());
        out.put("unprocessedSourceCount", unprocessed.size());
        out.put("completedSourceIds", List.copyOf(completed));
        out.put("failedSourceIds", List.copyOf(failed));
        out.put("unprocessedSourceIds", List.copyOf(unprocessed));
        out.put("plannedBatchCount", plannedBatches);
        out.put("successfulBatchCount", successfulBatches);
        out.put("failedBatchCount", failed.isEmpty() ? 0 : 1);
    }

    public static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> metadata(GraphRagExtractionResponse r) {
        if (r == null) {
            throw invalid("NULL_RESPONSE");
        }
        return map(r.getMetadata());
    }

    private static boolean emptyCandidates(GraphRagExtractionResponse r) {
        return r.getEntities() != null && r.getEntities().isEmpty() && r.getRelations() != null
                && r.getRelations().isEmpty() && r.getEvidences() != null && r.getEvidences().isEmpty()
                && r.getCommunities() != null && r.getCommunities().isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            throw invalid("MAP_REQUIRED");
        }
        return (Map<String, Object>) values;
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> values)) {
            throw invalid("LIST_REQUIRED");
        }
        return values.stream().map(GraphRagCandidateExecution::map).toList();
    }

    private static String string(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid("STRING_REQUIRED");
        }
        return text;
    }

    private static String fingerprint(Object value) {
        String fingerprint = string(value);
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw invalid("FINGERPRINT_INVALID");
        }
        return fingerprint;
    }

    private static long number(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw invalid("INTEGER_REQUIRED");
        }
        return ((Number) value).longValue();
    }

    private static boolean equivalentOptions(Object actual, Map<String, Object> expected) {
        if (!(actual instanceof Map<?, ?> actualOptions) || expected == null
                || !actualOptions.keySet().equals(expected.keySet())) {
            return false;
        }
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            Object actualValue = actualOptions.get(entry.getKey());
            Object expectedValue = entry.getValue();
            if (actualValue instanceof Number && expectedValue instanceof Number) {
                if (number(actualValue) != number(expectedValue)) {
                    return false;
                }
            }
            else if (!Objects.equals(actualValue, expectedValue)) {
                return false;
            }
        }
        return true;
    }

    private static void match(Map<String, Object> metadata, String key, Object expected) {
        if (!Objects.equals(metadata.get(key), expected)) {
            throw invalid("IDENTITY_MISMATCH: " + key);
        }
    }

    private static Set<Long> chunkIds(List<Map<String, Object>> segments) {
        Set<Long> chunkIds = new LinkedHashSet<>();
        for (Map<String, Object> segment : segments) {
            chunkIds.add(number(segment.get("chunkId")));
        }
        return chunkIds;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("FAILED_CONTRACT[" + VERSION + "]: " + reason);
    }

    public static final class CandidateExecutionException extends IllegalStateException {
        private final Map<String, Object> metadata;

        CandidateExecutionException(String message, Throwable cause, Map<String, Object> metadata) {
            super(message, cause);
            this.metadata = Map.copyOf(metadata);
        }

        public Map<String, Object> metadata() {
            return metadata;
        }
    }
}
