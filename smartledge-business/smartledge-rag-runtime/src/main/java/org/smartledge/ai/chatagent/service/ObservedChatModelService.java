package org.smartledge.ai.chatagent.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.rag.runtime.model.ChatModelUsageTrace;
import org.smartledge.ai.rag.runtime.model.ChatResult;
import org.smartledge.ai.rag.runtime.model.ModelCallException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

import org.smartledge.ai.rag.runtime.port.ObservedChatModelPort;
import org.smartledge.ai.rag.runtime.port.ChatModelPort;
import org.smartledge.ai.rag.runtime.port.ModelObservationSink;
import org.smartledge.ai.rag.runtime.model.ChatCallOptions;

/**
 * @description: 服务层
 * @author: Song
 **/

@Slf4j
@Service
public class ObservedChatModelService implements ObservedChatModelPort {

    private final ChatModelPort modelPort;

    public ObservedChatModelService(ChatModelPort modelPort) {
        this.modelPort = modelPort;
    }

    @Override
    public String callText(String stageName, String systemPrompt, String userPrompt) {
        return callText(stageName, systemPrompt, userPrompt, null, null);
    }

    public String callText(String stageName, String systemPrompt, String userPrompt,
                           ModelObservationSink sink) {
        return callText(stageName, systemPrompt, userPrompt, null, sink);
    }

    @Override
    public String callText(String stageName, String systemPrompt, String userPrompt,
                           ChatCallOptions options,
                           ModelObservationSink sink) {
        long started = System.nanoTime();
        String model = options != null && options.getModel() != null ? options.getModel() : modelPort.model();
        try {
            var response = modelPort.call(systemPrompt, userPrompt, options);
            var usage = response.usage();
            int promptTokens = usage != null && usage.promptTokens() != null ? usage.promptTokens()
                : estimateTokens(systemPrompt) + estimateTokens(userPrompt);
            int completionTokens = usage != null && usage.completionTokens() != null ? usage.completionTokens()
                : estimateTokens(response.text());
            int totalTokens = usage != null && usage.totalTokens() != null ? usage.totalTokens() : promptTokens + completionTokens;
            int providerFields = usage == null ? 0 : (usage.promptTokens() == null ? 0 : 1)
                + (usage.completionTokens() == null ? 0 : 1) + (usage.totalTokens() == null ? 0 : 1);
            if (sink != null) {
                sink.addModelUsageTrace(ChatModelUsageTrace.builder()
                    .stageName(stageName).provider("openai-compatible").model(response.model())
                    .responseId(response.responseId()).finishReason(response.finishReason())
                    .usageSource(providerFields == 0 ? "ESTIMATED" : providerFields == 3 ? "PROVIDER" : "MIXED")
                    .promptTokensEstimated(usage == null || usage.promptTokens() == null)
                    .completionTokensEstimated(usage == null || usage.completionTokens() == null)
                    .totalTokensEstimated(usage == null || usage.totalTokens() == null)
                    .promptTokens(promptTokens).completionTokens(completionTokens).totalTokens(totalTokens)
                    .estimatedCost(estimateCost(response.model(), promptTokens, completionTokens))
                    .durationMs((System.nanoTime() - started) / 1_000_000).status("COMPLETED").build());
            }
            return response.text();
        }
        catch (RuntimeException failure) {
            if (sink != null) {
                sink.addModelUsageTrace(ChatModelUsageTrace.builder().stageName(stageName)
                    .provider("openai-compatible").model(model).usageSource("ESTIMATED").promptTokensEstimated(true)
                    .promptTokens(estimateTokens(systemPrompt) + estimateTokens(userPrompt))
                    .durationMs((System.nanoTime() - started) / 1_000_000)
                    .status(Thread.currentThread().isInterrupted() ? "CANCELLED" : "FAILED").build());
            }
            throw failure;
        }
    }

    public Flux<String> streamText(String stageName, String systemPrompt, String userPrompt,
                                   ModelObservationSink sink) {
        return Flux.defer(() -> {
            long started = System.nanoTime();
            var facts = new AtomicReference<ChatResult>();
            var output = new StringBuffer();
            var recorded = new AtomicBoolean();
            java.util.function.Consumer<String> record = status -> {
                if (!recorded.compareAndSet(false, true) || sink == null) { return; }
                var response = facts.get();
                var usage = response == null ? null : response.usage();
                int prompt = usage != null && usage.promptTokens() != null ? usage.promptTokens()
                    : estimateTokens(systemPrompt) + estimateTokens(userPrompt);
                int completion = usage != null && usage.completionTokens() != null ? usage.completionTokens() : estimateTokens(output.toString());
                int total = usage != null && usage.totalTokens() != null ? usage.totalTokens() : prompt + completion;
                int fields = usage == null ? 0 : (usage.promptTokens() == null ? 0 : 1)
                    + (usage.completionTokens() == null ? 0 : 1) + (usage.totalTokens() == null ? 0 : 1);
                String model = response == null || response.model() == null ? modelPort.model() : response.model();
                sink.addModelUsageTrace(ChatModelUsageTrace.builder().stageName(stageName).provider("openai-compatible")
                    .model(model).responseId(response == null ? null : response.responseId())
                    .finishReason(response == null ? null : response.finishReason())
                    .usageSource(fields == 0 ? "ESTIMATED" : fields == 3 ? "PROVIDER" : "MIXED")
                    .promptTokensEstimated(usage == null || usage.promptTokens() == null)
                    .completionTokensEstimated(usage == null || usage.completionTokens() == null)
                    .totalTokensEstimated(usage == null || usage.totalTokens() == null)
                    .promptTokens(prompt).completionTokens(completion).totalTokens(total)
                    .estimatedCost(estimateCost(model, prompt, completion))
                    .durationMs((System.nanoTime() - started) / 1_000_000).status(status).build());
            };
            return modelPort.stream(systemPrompt, userPrompt, null)
                .<String>handle((event, downstream) -> {
                    facts.set(event.facts());
                    if (event.completed() && !"stop".equals(event.facts().finishReason())) {
                        downstream.error(new ModelCallException(
                            ModelCallException.Kind.INCOMPLETE, 200,
                            "rag-answer-" + event.facts().finishReason()));
                    } else if (!event.textDelta().isEmpty()) {
                        output.append(event.textDelta()); downstream.next(event.textDelta());
                    }
                })
                .doOnComplete(() -> record.accept("COMPLETED"))
                .doOnError(error -> record.accept(error instanceof ModelCallException failure
                    && failure.kind() == ModelCallException.Kind.CANCELLED ? "CANCELLED" : "FAILED"))
                .doOnCancel(() -> record.accept("CANCELLED"));
        });
    }

    private Integer estimateTokens(String content) {
        if (StrUtil.isBlank(content)) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(content.trim().length() / 4.0));
    }

    private Double estimateCost(String model, Integer promptTokens, Integer completionTokens) {
        if ((promptTokens == null || promptTokens <= 0) && (completionTokens == null || completionTokens <= 0)) {
            return null;
        }
        String normalizedModel = StrUtil.blankToDefault(model, "").toLowerCase();
        double promptRatePer1k;
        double completionRatePer1k;
        if (normalizedModel.contains("qwen-plus")) {
            promptRatePer1k = 0.004;
            completionRatePer1k = 0.012;
        }
        else if (normalizedModel.contains("deepseek")) {
            promptRatePer1k = 0.002;
            completionRatePer1k = 0.008;
        }
        else {
            promptRatePer1k = 0.0;
            completionRatePer1k = 0.0;
        }
        double promptCost = (promptTokens == null ? 0D : promptTokens / 1000D) * promptRatePer1k;
        double completionCost = (completionTokens == null ? 0D : completionTokens / 1000D) * completionRatePer1k;
        double total = promptCost + completionCost;
        return total > 0D ? total : null;
    }
}
