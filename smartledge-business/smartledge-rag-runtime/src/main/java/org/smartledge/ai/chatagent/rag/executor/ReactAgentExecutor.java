package org.smartledge.ai.chatagent.rag.executor;

import org.smartledge.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.smartledge.ai.chatagent.rag.model.ExecutionMode;
import org.smartledge.ai.chatagent.rag.support.ExecutorEventSupport;
import org.smartledge.ai.chatagent.service.ConversationTraceRecorder;
import org.smartledge.ai.chatagent.service.TaskInfo;
import org.smartledge.ai.chatagent.support.StreamEventWriter;
import org.smartledge.ai.prompt.PromptTemplateNames;
import org.smartledge.ai.prompt.PromptTemplateService;
import org.smartledge.ai.rag.runtime.agent.AgentState;
import org.smartledge.ai.rag.runtime.agent.AgentStatePort;
import org.smartledge.ai.chatagent.agent.AgentTool;
import org.smartledge.ai.chatagent.agent.AgentToolContext;
import org.smartledge.ai.chatagent.agent.ToolCallOutcome;
import org.smartledge.ai.rag.runtime.config.ChatAgentProperties;
import org.smartledge.ai.rag.runtime.model.*;
import org.smartledge.ai.rag.runtime.port.ChatModelPort;
import org.smartledge.database.identity.IdentityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** The single production REACT_AGENT executor. History is committed only at replayable boundaries. */
@Component
public class ReactAgentExecutor implements ConversationExecutor {
    private final ChatModelPort model;
    private final AgentStatePort states;
    private final Map<String, AgentTool> tools;
    private final ChatAgentProperties properties;
    private final StreamEventWriter writer;
    private final PromptTemplateService prompts;

    public ReactAgentExecutor(ChatModelPort model, AgentStatePort states, List<AgentTool> tools,
                              ChatAgentProperties properties, StreamEventWriter writer, PromptTemplateService prompts) {
        this.model = model; this.states = states; this.properties = properties; this.writer = writer;
        this.prompts = prompts;
        Map<String, AgentTool> registered = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (registered.putIfAbsent(tool.definition().name(), tool) != null) throw new IllegalArgumentException("Duplicate tool");
        }
        this.tools = Collections.unmodifiableMap(registered);
    }
    @Override public ExecutionMode mode() { return ExecutionMode.REACT_AGENT; }

    @Override public Flux<String> execute(TaskInfo task) {
        return Flux.defer(() -> {
            new AgentToolContext(task).checkActive();
            Run run = new Run(task);
            return run.start().thenMany(Flux.defer(run::next))
                .doOnSubscribe(subscription -> task.attachAgentExecution(subscription::cancel))
                .doOnComplete(() -> run.close(null))
                .doOnError(run::close)
                .doOnCancel(() -> { task.agentCancelled().set(true); run.close(new CancellationException("CANCELLED")); });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private final class Run {
        final TaskInfo task;
        final AgentToolContext context;
        final int modelRunLimit, modelThreadLimit, toolRunLimit, toolThreadLimit;
        final AtomicBoolean closed = new AtomicBoolean();
        AgentState state;
        int modelRun, toolRun;
        ConversationTraceRecorder.StageHandle stage;

        Run(TaskInfo task) {
            this.task = task; this.context = new AgentToolContext(task);
            var limits = properties.snapshot();
            modelRunLimit = limits.modelRun(); modelThreadLimit = limits.modelThread();
            toolRunLimit = limits.toolRun(); toolThreadLimit = limits.toolThread();
        }
        Mono<Void> start() {
            return Mono.fromRunnable(() -> {
                synchronized (task) {
                    context.checkActive();
                    ExecutorEventSupport.publishThinking(task, writer, "当前问题进入开放式 Agent 自主执行阶段。");
                    task.debugTrace().getRetrievalNotes().add("当前问题走 ReactAgent 执行路径，由 Agent 自主决定是否调用知识库检索、长期记忆或联网搜索。");
                    stage = task.traceRecorder() == null ? null : task.traceRecorder().startStage(
                        ConversationTraceStageCode.REACT_AGENT, mode().name(), "正在执行 ReAct Agent 推理与工具调用。", null);
                    state = withTaskOperator(task, () -> states.begin(task.conversationId(), task.exchangeId()));
                    List<ChatMessage> history = new ArrayList<>(state.messages());
                    history.add(ChatMessage.text("user", task.executionPlan().getAgentQuestion()));
                    save(state.modelCalls(), state.toolCalls(), history, true);
                }
            });
        }
        Flux<String> next() {
            synchronized (task) {
                context.checkActive();
                if (modelRun >= modelRunLimit || state.modelCalls() >= modelThreadLimit) return limit("MODEL_LIMIT");
                if (toolRun >= toolRunLimit || state.toolCalls() >= toolThreadLimit) return limit("TOOL_LIMIT");
                // Reserve before network dispatch; failed/cancelled attempts cannot reset thread budgets.
                save(state.modelCalls() + 1, state.toolCalls(), state.messages(), false); modelRun++;
            }
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.text("system", systemPrompt(task))); messages.addAll(state.messages());
            StringBuilder text = new StringBuilder();
            ChatResult[] completed = new ChatResult[1];
            return model.stream(messages, tools.values().stream().map(AgentTool::definition).toList(), ChatCallOptions.builder().build())
                .publishOn(Schedulers.boundedElastic())
                .handle((event, sink) -> {
                    synchronized (task) {
                        context.checkActive();
                        if (event.textDelta() != null && !event.textDelta().isEmpty()) {
                            text.append(event.textDelta()); sink.next(event.textDelta());
                        }
                        if (event.completed()) completed[0] = event.facts();
                    }
                }).cast(String.class)
                .concatWith(Flux.defer(() -> afterModel(text.toString(), completed[0])));
        }
        Flux<String> afterModel(String text, ChatResult result) {
            if (result == null) return Flux.error(new IllegalStateException("Agent model missing completion"));
            if ("stop".equals(result.finishReason()) && result.toolCalls().isEmpty()) {
                synchronized (task) {
                    context.checkActive();
                    List<ChatMessage> history = new ArrayList<>(state.messages());
                    history.add(ChatMessage.text("assistant", text));
                    save(state.modelCalls(), state.toolCalls(), history, true);
                }
                return Flux.empty();
            }
            if (!"tool_calls".equals(result.finishReason()) || result.toolCalls().isEmpty())
                return Flux.error(new IllegalStateException("Agent model incomplete: " + result.finishReason()));
            List<ChatResult.ToolCall> calls = result.toolCalls();
            synchronized (task) {
                context.checkActive();
                if (calls.size() > Math.min(toolRunLimit - toolRun, toolThreadLimit - state.toolCalls())) return limit("TOOL_LIMIT");
                save(state.modelCalls(), state.toolCalls() + calls.size(), state.messages(), false); toolRun += calls.size();
            }
            return Flux.fromIterable(calls)
                .flatMapSequential(call -> Mono.using(() -> new AgentToolContext(task), toolContext ->
                    Mono.fromCallable(() -> withTaskOperator(task, () -> invoke(call, toolContext)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(tools.containsKey(call.name()) ? tools.get(call.name()).timeout() : java.time.Duration.ofSeconds(30)),
                    AgentToolContext::cancel, true)
                    .onErrorResume(java.util.concurrent.TimeoutException.class,
                        error -> Mono.just(unknownTimeout(call))),
                    4, 1)
                .collectList().flatMapMany(results -> {
                    synchronized (task) {
                        context.checkActive();
                        List<ChatMessage> history = new ArrayList<>(state.messages());
                        history.add(new ChatMessage("assistant", text, calls, null)); history.addAll(results);
                        save(state.modelCalls(), state.toolCalls(), history, true);
                    }
                    return Flux.defer(this::next);
                });
        }
        ChatMessage invoke(ChatResult.ToolCall call, AgentToolContext context) throws Exception {
            long started = System.currentTimeMillis();
            context.checkActive();
            AgentTool tool = tools.get(call.name());
            if (tool == null) {
                ToolCallOutcome outcome = ToolCallOutcome.rejected(call.name(), "UNKNOWN_TOOL", "未知工具: " + call.name());
                context.recordOutcome(outcome);
                return toolResult(call, outcome.toEnvelope());
            }
            for (int attempt = 0; ; attempt++) {
                context.checkActive();
                try {
                    String result = tool.execute(call.arguments(), context);
                    context.checkActive();
                    if (result == null) throw new IllegalStateException("Empty tool result");
                    context.recordOutcome(ToolCallOutcome.succeeded(call.name(), elapsed(started)));
                    return toolResult(call, result);
                } catch (CancellationException e) {
                    context.recordOutcome(ToolCallOutcome.cancelled(call.name(), elapsed(started)));
                    throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    context.recordOutcome(ToolCallOutcome.cancelled(call.name(), elapsed(started)));
                    throw new CancellationException("Agent cancelled");
                } catch (Exception e) {
                    context.checkActive();
                    if (e instanceof IllegalArgumentException || attempt == 2) {
                        ToolCallOutcome outcome = ToolCallOutcome.failed(
                            call.name(),
                            e.getClass().getSimpleName(),
                            "工具调用失败",
                            elapsed(started)
                        );
                        context.recordOutcome(outcome);
                        return toolResult(call, outcome.toEnvelope());
                    }
                    // One logical tool reservation, at most three physical attempts. Hard cap includes jitter.
                    long delay = Math.min(1200, (long) (200 * (1L << attempt) * ThreadLocalRandom.current().nextDouble(.75, 1.25)));
                    Thread.sleep(delay);
                }
            }
        }
        ChatMessage unknownTimeout(ChatResult.ToolCall call) {
            ToolCallOutcome outcome = ToolCallOutcome.unknown(call.name(), "TIMEOUT", "工具超时，结果未知", 0L);
            context.recordOutcome(outcome);
            return toolResult(call, outcome.toEnvelope());
        }
        ChatMessage toolResult(ChatResult.ToolCall call, String result) {
            return new ChatMessage("tool", result, List.of(), call.id());
        }
        Flux<String> limit(String reason) {
            synchronized (task) {
                context.checkActive();
                task.debugTrace().getRetrievalNotes().add(reason);
                ExecutorEventSupport.publishThinking(task, writer, reason);
            }
            // END remains a normal finite termination; no invented model answer is published or checkpointed.
            return Flux.empty();
        }
        void save(int models, int toolCount, List<ChatMessage> history, boolean checkpoint) {
            context.checkActive();
            state = withTaskOperator(task, () -> states.save(state, models, toolCount, history, checkpoint));
        }
        void close(Throwable error) {
            synchronized (task) {
                if (!closed.compareAndSet(false, true)) return;
                try { if (state != null) withTaskOperator(task, () -> { states.finish(state); return null; }); }
                finally {
                    if (task.traceRecorder() != null && stage != null) {
                        if (error == null) task.traceRecorder().completeStage(stage, "ReAct Agent 执行完成。", Map.of(
                            "toolNames", task.debugTrace().getToolTraces(),
                            "usedTools", task.usedTools(),
                            "toolOutcomes", task.toolOutcomes().stream().map(ToolCallOutcome::toEnvelope).toList()));
                        else task.traceRecorder().failStage(stage, "ReAct Agent 执行失败。", error.getMessage(), null);
                    }
                }
            }
        }
    }

    private String systemPrompt(TaskInfo task) {
        var plan = task.executionPlan();
        return prompts.render(PromptTemplateNames.CHAT_AGENT_SYSTEM, Map.of(
            "longTermFacts", plan == null || plan.getLongTermFactsText() == null ? "" : plan.getLongTermFactsText(),
            "longTermSummary", plan == null || plan.getLongTermSummary() == null ? "" : plan.getLongTermSummary()
        ));
    }

    private static long elapsed(long started) {
        return Math.max(0L, System.currentTimeMillis() - started);
    }

    private static <T> T withTaskOperator(TaskInfo task, OperatorAction<T> action) {
        return IdentityContext.callWith(task.operator(), () -> {
            try {
                return action.run();
            }
            catch (RuntimeException exception) {
                throw exception;
            }
            catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    @FunctionalInterface
    private interface OperatorAction<T> {
        T run() throws Exception;
    }
}
