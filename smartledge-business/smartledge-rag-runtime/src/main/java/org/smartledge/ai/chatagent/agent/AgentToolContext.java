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

/** Per-exchange tool effects owned by the execution consumer in runtime, not a general TaskInfo escape hatch. */
public final class AgentToolContext {
    private final TaskInfo task;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public AgentToolContext(TaskInfo task) { this.task = task; }
    public void cancel() { synchronized (task) { cancelled.set(true); } }
    public String question() { return task.question(); }
    public String currentDate() { return task.currentDate() == null ? "" : task.currentDate().toString(); }
    public void checkActive() {
        if (cancelled.get() || task.finalized().get() || task.agentCancelled().get() || Thread.currentThread().isInterrupted())
            throw new CancellationException("Agent cancelled");
    }
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
