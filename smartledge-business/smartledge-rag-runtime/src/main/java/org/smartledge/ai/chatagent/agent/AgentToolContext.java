package org.smartledge.ai.chatagent.agent;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.model.debug.ChatToolTrace;
import org.smartledge.ai.chatagent.service.TaskInfo;
import org.smartledge.ai.chatagent.support.SinkEmitHelper;
import org.smartledge.ai.chatagent.support.StreamEventWriter;
import org.smartledge.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.smartledge.ai.chatagent.rag.model.RetrievalPlan;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;

/** Per-exchange tool effects owned by the execution consumer in runtime, not a general TaskInfo escape hatch. */
public final class AgentToolContext {
    private final TaskInfo task;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public AgentToolContext(TaskInfo task) { this.task = task; }
    public void cancel() { synchronized (task) { cancelled.set(true); } }
    public String question() { return task.question(); }
    public String currentDate() { return task.currentDate() == null ? "" : task.currentDate().toString(); }
    public KnowledgeBaseSelectionSnapshot knowledgeScope() { return task.knowledgeBaseSelectionSnapshot(); }
    public RetrievalPlan authorizedRetrievalPlan() {
        ConversationExecutionPlan plan = task.executionPlan();
        return plan == null ? null : plan.getRetrievalPlan();
    }
    public String conversationId() { return task.conversationId(); }
    public long exchangeId() { return task.exchangeId(); }
    public Long userId() { return task.userId(); }
    public List<ToolCallOutcome> toolOutcomes() { return List.copyOf(task.toolOutcomes()); }
    public void recordOutcome(ToolCallOutcome outcome) {
        if (outcome == null) {
            return;
        }
        synchronized (task) {
            task.toolOutcomes().add(outcome);
        }
    }
    public void checkActive() {
        if (cancelled.get() || task.finalized().get() || task.agentCancelled().get() || Thread.currentThread().isInterrupted())
            throw new CancellationException("Agent cancelled");
    }
    public List<SearchReference> references() { return List.copyOf(task.references()); }
    public void addReferences(List<SearchReference> references) { update(() -> task.references().addAll(references)); }
    public void markToolUsed(String name) { update(() -> task.usedTools().add(name)); }
    public void thinking(String content, StreamEventWriter writer) {
        update(() -> {
            SinkEmitHelper.emitNext(task.sink(), writer.thinking(content, task.eventMetadata()));
            task.thinkingSteps().add(content);
        });
    }
    public void registerTrace(ChatToolTrace trace) { update(() -> task.debugTrace().getToolTraces().add(trace)); }
    public void updateTrace(ChatToolTrace trace, Consumer<ChatToolTrace> mutation) { update(() -> mutation.accept(trace)); }
    private void update(Runnable mutation) { synchronized (task) { checkActive(); mutation.run(); } }
}
