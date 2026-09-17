package org.smartledge.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import org.smartledge.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.smartledge.ai.chatagent.model.trace.RouteTraceSnapshot;
import org.smartledge.ai.chatagent.model.trace.RouteTraceSnapshot.Substage;
import org.smartledge.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.smartledge.ai.chatagent.rag.model.ExecutionMode;
import org.smartledge.ai.chatagent.rag.support.ExecutorEventSupport;
import org.smartledge.ai.chatagent.service.TaskInfo;
import org.smartledge.ai.chatagent.support.StreamEventWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * @description: 路由歧义澄清执行器
 * @author: Song
 **/

@Component
public class ClarificationExecutor implements ConversationExecutor {

    private final StreamEventWriter streamEventWriter;

    public ClarificationExecutor(StreamEventWriter streamEventWriter) {
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.CLARIFICATION;
    }

    @Override
    public Flux<String> execute(TaskInfo taskInfo) {
        ConversationExecutionPlan plan = taskInfo.executionPlan();
        String clarificationReply = plan == null
            ? "当前我无法稳定判断你想问哪份知识文档，请补充更具体的文档名、主题或关键词。"
            : StrUtil.blankToDefault(plan.getClarificationReply(),
                "当前我无法稳定判断你想问哪份知识文档，请补充更具体的文档名、主题或关键词。");
        String clarificationReason = plan == null ? "" : StrUtil.blankToDefault(plan.getClarificationReason(), "");
        if (taskInfo.debugTrace() != null && StrUtil.isNotBlank(clarificationReason)) {
            taskInfo.debugTrace().getRetrievalNotes().add(clarificationReason);
        }

        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "当前问题涉及多份候选文档，先向你确认知识范围。");
        if (StrUtil.isNotBlank(clarificationReason)) {
            ExecutorEventSupport.publishStatus(taskInfo, streamEventWriter, clarificationReason);
        }
        if (taskInfo.traceRecorder() != null) {
            Map<String, Object> routeSnapshot = RouteTraceSnapshot.of(Substage.CLARIFICATION_RESPONSE, Map.of(
                "clarificationReply", clarificationReply,
                "clarificationReason", clarificationReason,
                "clarificationOptions", plan == null || plan.getClarificationOptions() == null ? List.of() : plan.getClarificationOptions()
            ));
            taskInfo.traceRecorder().completeStage(
                taskInfo.traceRecorder().startStage(
                    ConversationTraceStageCode.ROUTE,
                    mode().name(),
                    "当前候选存在歧义，先返回澄清问题。",
                    routeSnapshot
                ),
                "已返回澄清问题。",
                routeSnapshot
            );
        }
        return Flux.just(clarificationReply);
    }
}
