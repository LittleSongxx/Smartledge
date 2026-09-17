package org.smartledge.ai.chatagent.service;

import lombok.Data;
import org.smartledge.ai.chatagent.model.debug.ChatDebugTrace;
import org.smartledge.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.smartledge.ai.chatagent.rag.model.RagPromptAssemblyResult;
import org.smartledge.ai.chatagent.model.SearchReference;
import org.smartledge.ai.chatagent.support.StreamEventMetadata;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseSelectionSnapshot;
import org.smartledge.enums.ChatQueryMode;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import org.smartledge.ai.chatagent.agent.ToolCallOutcome;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @description: 服务层
 * @author: Song
 **/

@Data
public class TaskInfo {
    private final String conversationId;
    private final long exchangeId;

    private final String question;
    private final ChatQueryMode chatMode;
    private final String traceId;

    /**
     * 本次对话执行所属租户。
     *
     * <p>它是租户上下文在异步链路里的**载体**。{@code TenantContext} 是 ThreadLocal，
     * 而对话执行会跨 Reactor {@code boundedElastic}、{@code chat-rag-executor} 线程池
     * 与模型流式回调线程；收尾落库、租约失效停止、客户端取消停止这些终端回调都在请求
     * 线程返回之后才执行，只有跟着本轮执行走的字段还能拿到当时的租户。</p>
     */
    private final Long tenantId;
    private final Long userId;
    private final Long selectedDocumentId;
    private final String selectedDocumentName;
    private final Long selectedTaskId;
    private final KnowledgeBaseSelectionSnapshot knowledgeBaseSelectionSnapshot;
    private final LocalDate currentDate;
    private final String currentDateText;

    private volatile ConversationExecutionPlan executionPlan;

    private volatile RagPromptAssemblyResult promptAssemblyResult;

    private volatile ChatDebugTrace debugTrace;

    private final ConversationTraceRecorder traceRecorder;

    private final Sinks.Many<String> sink;
    private final StreamEventMetadata eventMetadata;
    private final String leaseKey;
    private final String leaseOwnerToken;

    private final StringBuffer answerBuffer = new StringBuffer();

    private final List<String> thinkingSteps;
    private final List<SearchReference> references;
    private final Set<String> usedTools;
    private final List<ToolCallOutcome> toolOutcomes = Collections.synchronizedList(new ArrayList<>());

    private final long startTime;

    private final AtomicLong firstResponseTimeMs = new AtomicLong(0L);
    private final AtomicBoolean finalized = new AtomicBoolean(false);

    private final AtomicBoolean agentCancelled = new AtomicBoolean(false);
    private final reactor.core.Disposable.Swap agentSubscription = reactor.core.Disposables.swap();
    public AtomicBoolean agentCancelled() { return agentCancelled; }
    public synchronized boolean tryFinalize() { return finalized.compareAndSet(false, true); }
    public void cancelAgentExecution() { agentCancelled.set(true); agentSubscription.dispose(); }
    public void attachAgentExecution(Disposable value) { agentSubscription.update(value); }

    private volatile Disposable disposable;
    private volatile Disposable leaseRenewalDisposable;

    public TaskInfo(String conversationId,
                    long exchangeId,
                    String question,
                    ChatQueryMode chatMode,
                    String traceId,
                    Long tenantId,
                    Long selectedDocumentId,
                    String selectedDocumentName,
                    Long selectedTaskId,
                    KnowledgeBaseSelectionSnapshot knowledgeBaseSelectionSnapshot,
                    LocalDate currentDate,
                    String currentDateText,
                    ConversationExecutionPlan executionPlan,
                    ChatDebugTrace debugTrace,
                    ConversationTraceRecorder traceRecorder,
                    Sinks.Many<String> sink,
                    StreamEventMetadata eventMetadata,
                    String leaseKey,
                    String leaseOwnerToken,
                    List<String> thinkingSteps,
                    List<SearchReference> references,
                    Set<String> usedTools,
                    long startTime) {
        this(conversationId, exchangeId, question, chatMode, traceId, tenantId, null,
            selectedDocumentId, selectedDocumentName, selectedTaskId, knowledgeBaseSelectionSnapshot,
            currentDate, currentDateText, executionPlan, debugTrace, traceRecorder, sink, eventMetadata,
            leaseKey, leaseOwnerToken, thinkingSteps, references, usedTools, startTime);
    }

    public TaskInfo(String conversationId,
                    long exchangeId,
                    String question,
                    ChatQueryMode chatMode,
                    String traceId,
                    Long tenantId,
                    Long userId,
                    Long selectedDocumentId,
                    String selectedDocumentName,
                    Long selectedTaskId,
                    KnowledgeBaseSelectionSnapshot knowledgeBaseSelectionSnapshot,
                    LocalDate currentDate,
                    String currentDateText,
                    ConversationExecutionPlan executionPlan,
                    ChatDebugTrace debugTrace,
                    ConversationTraceRecorder traceRecorder,
                    Sinks.Many<String> sink,
                    StreamEventMetadata eventMetadata,
                    String leaseKey,
                    String leaseOwnerToken,
                    List<String> thinkingSteps,
                    List<SearchReference> references,
                    Set<String> usedTools,
                    long startTime) {
        this.conversationId = conversationId;
        this.exchangeId = exchangeId;
        this.question = question;
        this.chatMode = chatMode;
        this.traceId = traceId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.selectedDocumentId = selectedDocumentId;
        this.selectedDocumentName = selectedDocumentName;
        this.selectedTaskId = selectedTaskId;
        this.knowledgeBaseSelectionSnapshot = knowledgeBaseSelectionSnapshot;
        this.currentDate = currentDate;
        this.currentDateText = currentDateText;
        this.executionPlan = executionPlan;
        this.debugTrace = debugTrace;
        this.traceRecorder = traceRecorder;
        this.sink = sink;
        this.eventMetadata = eventMetadata;
        this.leaseKey = leaseKey;
        this.leaseOwnerToken = leaseOwnerToken;
        this.thinkingSteps = thinkingSteps;
        this.references = references;
        this.usedTools = usedTools;
        this.startTime = startTime;
    }

    public String conversationId() {
        return conversationId;
    }

    public long exchangeId() {
        return exchangeId;
    }

    public String question() {
        return question;
    }

    public ChatQueryMode chatMode() {
        return chatMode;
    }

    public String traceId() {
        return traceId;
    }

    public Long tenantId() {
        return tenantId;
    }

    public Long userId() {
        return userId;
    }

    public ConversationTraceRecorder traceRecorder() {
        return traceRecorder;
    }

    public Long selectedDocumentId() {
        return selectedDocumentId;
    }

    public String selectedDocumentName() {
        return selectedDocumentName;
    }

    public Long selectedTaskId() {
        return selectedTaskId;
    }

    public KnowledgeBaseSelectionSnapshot knowledgeBaseSelectionSnapshot() {
        return knowledgeBaseSelectionSnapshot;
    }

    public LocalDate currentDate() {
        return currentDate;
    }

    public String currentDateText() {
        return currentDateText;
    }

    public ConversationExecutionPlan executionPlan() {
        return executionPlan;
    }

    public void setExecutionPlan(ConversationExecutionPlan executionPlan) {
        this.executionPlan = executionPlan;
    }

    public RagPromptAssemblyResult promptAssemblyResult() {
        return promptAssemblyResult;
    }

    public void setPromptAssemblyResult(RagPromptAssemblyResult promptAssemblyResult) {
        this.promptAssemblyResult = promptAssemblyResult;
    }

    public ChatDebugTrace debugTrace() {
        return debugTrace;
    }

    public void setDebugTrace(ChatDebugTrace debugTrace) {
        this.debugTrace = debugTrace;
    }

    public Sinks.Many<String> sink() {
        return sink;
    }

    public StreamEventMetadata eventMetadata() {
        return eventMetadata;
    }

    public String leaseKey() {
        return leaseKey;
    }

    public String leaseOwnerToken() {
        return leaseOwnerToken;
    }

    public StringBuffer answerBuffer() {
        return answerBuffer;
    }

    public List<String> thinkingSteps() {
        return thinkingSteps;
    }

    public List<SearchReference> references() {
        return references;
    }

    public Set<String> usedTools() {
        return usedTools;
    }

    public List<ToolCallOutcome> toolOutcomes() {
        return toolOutcomes;
    }

    public long startTime() {
        return startTime;
    }

    public AtomicLong firstResponseTimeMs() {
        return firstResponseTimeMs;
    }

    public AtomicBoolean finalized() {
        return finalized;
    }

    public Disposable disposable() {
        return disposable;
    }

    public Disposable leaseRenewalDisposable() {
        return leaseRenewalDisposable;
    }
}
