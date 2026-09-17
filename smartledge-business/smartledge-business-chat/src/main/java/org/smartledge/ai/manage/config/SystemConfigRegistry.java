package org.smartledge.ai.manage.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.smartledge.ai.manage.model.SystemConfigSnapshot;
import org.smartledge.ai.rag.runtime.model.RagRuntimeOptions;
import org.smartledge.enums.BaseCode;
import org.smartledge.exception.SuperAgentFrameException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Component
public class SystemConfigRegistry {

    public static final String NEW_CONVERSATION = "NEW_CONVERSATION";
    public static final String NEW_BUILD_TASK = "NEW_BUILD_TASK";
    public static final String RESTART_REQUIRED = "RESTART_REQUIRED";

    private final List<ConfigDefinition> definitions;
    private final Map<String, ConfigDefinition> definitionsByKey;

    public SystemConfigRegistry() {
        List<ConfigDefinition> rawDefinitions = List.of(
                integer("mvcAsync.corePoolSize", "chatAgent", "流式响应核心线程数", "MVC 流式响应写出线程池的常驻线程数。", 1, 256,
                        "线程", RESTART_REQUIRED, o -> o.getMvcAsync().getCorePoolSize(),
                        (o, v) -> o.getMvcAsync().setCorePoolSize((Integer) v)),
                integer("mvcAsync.maxPoolSize", "chatAgent", "流式响应最大线程数", "写出任务队列满后允许扩展到的最大池内线程数；持续满载时由提交线程执行以减缓上游。", 1, 256,
                        "线程", RESTART_REQUIRED, o -> o.getMvcAsync().getMaxPoolSize(),
                        (o, v) -> o.getMvcAsync().setMaxPoolSize((Integer) v)),
                integer("mvcAsync.queueCapacity", "chatAgent", "流式响应队列容量", "等待写出的任务上限；0 表示直接交接。该容量不是会话或消息缓存上限。", 0, 4096,
                        "任务", RESTART_REQUIRED, o -> o.getMvcAsync().getQueueCapacity(),
                        (o, v) -> o.getMvcAsync().setQueueCapacity((Integer) v)),
                integer("mvcAsync.keepAliveSeconds", "chatAgent", "流式响应空闲回收时间", "超过核心线程数的空闲线程保留时间。", 1, 3600,
                        "秒", RESTART_REQUIRED, o -> o.getMvcAsync().getKeepAliveSeconds(),
                        (o, v) -> o.getMvcAsync().setKeepAliveSeconds((Integer) v)),
                integer("mvcAsync.shutdownAwaitSeconds", "chatAgent", "流式响应关闭等待时间", "应用关闭时等待已提交写出任务的最长时间；不保证远端客户端已收到全部内容。", 0, 120,
                        "秒", RESTART_REQUIRED, o -> o.getMvcAsync().getShutdownAwaitSeconds(),
                        (o, v) -> o.getMvcAsync().setShutdownAwaitSeconds((Integer) v)),

                // 账号与口令不再走系统参数：B3 起凭据只存在于 smartledge_user（BCrypt 哈希），
                // 系统参数里既没有可读的明文口令，也没有可写的口令入口。
                secretText("adminAuth.tokenSecret", "adminAuth", "令牌签名密钥", "登录令牌签名密钥；轮换后旧令牌失效。", 512,
                        RESTART_REQUIRED, o -> o.getAdminAuth().getTokenSecret(),
                        (o, v) -> o.getAdminAuth().setTokenSecret((String) v)),
                longInteger("adminAuth.tokenExpireMinutes", "adminAuth", "令牌有效期", "后台登录令牌有效期，单位分钟。", 1, 10080,
                        "分钟", RESTART_REQUIRED, o -> o.getAdminAuth().getTokenExpireMinutes(),
                        (o, v) -> o.getAdminAuth().setTokenExpireMinutes((Long) v)),

                bool("previewMode.enabled", "previewMode", "启用只读预览模式", "按接口白名单限制管理写操作；不代表停止后台任务。", RESTART_REQUIRED,
                        o -> o.getPreviewMode().isEnabled(),
                        (o, v) -> o.getPreviewMode().setEnabled((Boolean) v)),
                text("previewMode.message", "previewMode", "预览模式提示语", "只读预览模式拦截请求时返回的提示文本。", 500,
                        RESTART_REQUIRED, o -> o.getPreviewMode().getMessage(),
                        (o, v) -> o.getPreviewMode().setMessage((String) v)),

                bool("evaluation.snapshot.enabled", "evaluationSnapshot", "启用评测快照", "是否开放 Evaluation Exchange Snapshot 只读接口。", RESTART_REQUIRED,
                        o -> o.getEvaluationSnapshot().isEnabled(),
                        (o, v) -> o.getEvaluationSnapshot().setEnabled((Boolean) v)),
                integer("evaluation.snapshot.maxBatchSize", "evaluationSnapshot", "评测快照批量上限", "一次快照请求允许读取的最大 exchange 数量。", 1, 2000,
                        "条", RESTART_REQUIRED, o -> o.getEvaluationSnapshot().getMaxBatchSize(),
                        (o, v) -> o.getEvaluationSnapshot().setMaxBatchSize((Integer) v)),
                integer("evaluation.snapshot.requestsPerMinute", "evaluationSnapshot", "评测快照请求频率", "评测快照接口按调用身份限制的每分钟请求次数。", 1, 10000,
                        "次/分钟", RESTART_REQUIRED, o -> o.getEvaluationSnapshot().getRequestsPerMinute(),
                        (o, v) -> o.getEvaluationSnapshot().setRequestsPerMinute((Integer) v)),
                text("evaluation.snapshot.promptVersion", "evaluationSnapshot", "评测 Prompt 版本", "固化到新 exchange 标签中的 Prompt 版本标识。", 128,
                        NEW_CONVERSATION, o -> o.getEvaluationSnapshot().getPromptVersion(),
                        (o, v) -> o.getEvaluationSnapshot().setPromptVersion((String) v)),
                bool("evaluation.retrievalProbe.enabled", "retrievalProbe", "启用检索探针", "是否开放管理员检索探针接口；探针不创建聊天 exchange。", RESTART_REQUIRED,
                        o -> o.getRetrievalProbe().isEnabled(),
                        (o, v) -> o.getRetrievalProbe().setEnabled((Boolean) v)),
                integer("evaluation.retrievalProbe.requestsPerMinute", "retrievalProbe", "检索探针请求频率", "检索探针按调用身份限制的每分钟请求次数。", 1, 10000,
                        "次/分钟", RESTART_REQUIRED, o -> o.getRetrievalProbe().getRequestsPerMinute(),
                        (o, v) -> o.getRetrievalProbe().setRequestsPerMinute((Integer) v)),
                integer("evaluation.retrievalProbe.maxQueryLength", "retrievalProbe", "检索探针查询长度", "检索探针查询文本的 Java 字符串长度上限。", 1, 20000,
                        "字符", RESTART_REQUIRED, o -> o.getRetrievalProbe().getMaxQueryLength(),
                        (o, v) -> o.getRetrievalProbe().setMaxQueryLength((Integer) v)),
                integer("evaluation.retrievalProbe.maxResultCount", "retrievalProbe", "检索探针结果上限", "检索探针允许返回的最大候选结果数量。", 1, 1000,
                        "条", RESTART_REQUIRED, o -> o.getRetrievalProbe().getMaxResultCount(),
                        (o, v) -> o.getRetrievalProbe().setMaxResultCount((Integer) v)),

                text("ragTools.baseUrl", "ragTools", "RAG 工具服务地址", "Java 调用 Python rag-tools 的统一服务地址。", 2048,
                        RESTART_REQUIRED, o -> o.getRagTools().getBaseUrl(),
                        (o, v) -> o.getRagTools().setBaseUrl((String) v)),
                integer("ragTools.connectTimeoutMs", "ragTools", "RAG 工具连接超时", "连接 Python rag-tools 的超时时间。", 1, 120000,
                        "毫秒", RESTART_REQUIRED, o -> o.getRagTools().getConnectTimeoutMs(),
                        (o, v) -> o.getRagTools().setConnectTimeoutMs((Integer) v)),
                integer("ragTools.documentParseReadTimeoutMs", "ragTools", "文档解析读取超时", "完整读取 Python 文档解析响应的超时时间。", 1, 3600000,
                        "毫秒", RESTART_REQUIRED, o -> o.getRagTools().getDocumentParseReadTimeoutMs(),
                        (o, v) -> o.getRagTools().setDocumentParseReadTimeoutMs((Integer) v)),
                integer("ragTools.graphExtractReadTimeoutMs", "graphRagExecution", "GraphRAG 读取超时", "完整读取 GraphRAG 计划或批次响应的超时时间。", 1, 3600000,
                        "毫秒", RESTART_REQUIRED, o -> o.getRagTools().getGraphExtractReadTimeoutMs(),
                        (o, v) -> o.getRagTools().setGraphExtractReadTimeoutMs((Integer) v)),
                integer("ragTools.graphExtractionResponseMaxBytes", "ragTools", "GraphRAG 响应字节上限", "Java 接收 Python GraphRAG 计划或批次响应的字节上限。", 1, 67108864,
                        "字节", RESTART_REQUIRED, o -> o.getRagTools().getGraphExtractionResponseMaxBytes(),
                        (o, v) -> o.getRagTools().setGraphExtractionResponseMaxBytes((Integer) v)),
                integer("ragTools.raptorBuildReadTimeoutMs", "ragTools", "RAPTOR 读取超时", "完整读取 Python RAPTOR 构建响应的超时时间。", 1, 3600000,
                        "毫秒", RESTART_REQUIRED, o -> o.getRagTools().getRaptorBuildReadTimeoutMs(),
                        (o, v) -> o.getRagTools().setRaptorBuildReadTimeoutMs((Integer) v)),

                bool("graphRag.diagnostics.enabled", "graphRagDiagnostics", "启用 GraphRAG 诊断", "是否保存候选拒绝诊断文件。", RESTART_REQUIRED,
                        o -> o.getGraphRagDiagnostics().isEnabled(),
                        (o, v) -> o.getGraphRagDiagnostics().setEnabled((Boolean) v)),
                text("graphRag.diagnostics.directory", "graphRagDiagnostics", "GraphRAG 诊断目录", "候选拒绝诊断文件输出目录。", 1024,
                        RESTART_REQUIRED, o -> o.getGraphRagDiagnostics().getDirectory(),
                        (o, v) -> o.getGraphRagDiagnostics().setDirectory((String) v)),
                integer("graphRag.diagnostics.maxTextChars", "graphRagDiagnostics", "诊断文本长度上限", "单个诊断文本字段的 Unicode 码点上限。", 1, 16384,
                        "字符", RESTART_REQUIRED, o -> o.getGraphRagDiagnostics().getMaxTextChars(),
                        (o, v) -> o.getGraphRagDiagnostics().setMaxTextChars((Integer) v)),
                integer("graphRag.diagnostics.maxFileBytes", "graphRagDiagnostics", "诊断文件字节上限", "单份诊断 JSON 的 UTF-8 字节上限。", 1024, 16777216,
                        "字节", RESTART_REQUIRED, o -> o.getGraphRagDiagnostics().getMaxFileBytes(),
                        (o, v) -> o.getGraphRagDiagnostics().setMaxFileBytes((Integer) v)),
                integer("graphRag.diagnostics.maxFiles", "graphRagDiagnostics", "诊断文件数量上限", "诊断目录最多保留的文件数量。", 1, 100,
                        "个", RESTART_REQUIRED, o -> o.getGraphRagDiagnostics().getMaxFiles(),
                        (o, v) -> o.getGraphRagDiagnostics().setMaxFiles((Integer) v)),

                integer("graphRag.execution.workerThreads", "graphRagExecution", "GraphRAG 工作线程", "每个 Java 实例 GraphRAG 共享池的固定工作线程数。", 1, 64,
                        "线程", RESTART_REQUIRED, o -> o.getGraphRagExecution().getWorkerThreads(),
                        (o, v) -> o.getGraphRagExecution().setWorkerThreads((Integer) v)),
                integer("graphRag.execution.queueCapacity", "graphRagExecution", "GraphRAG 队列容量", "GraphRAG 共享池允许排队的批次数量。", 1, 4096,
                        "任务", RESTART_REQUIRED, o -> o.getGraphRagExecution().getQueueCapacity(),
                        (o, v) -> o.getGraphRagExecution().setQueueCapacity((Integer) v)),
                integer("graphRag.execution.documentConcurrency", "graphRagExecution", "GraphRAG 文档并发", "单篇文档允许同时执行的批次数量。", 1, 64,
                        "批", RESTART_REQUIRED, o -> o.getGraphRagExecution().getDocumentConcurrency(),
                        (o, v) -> o.getGraphRagExecution().setDocumentConcurrency((Integer) v)),
                integer("graphRag.execution.maxBatchAttempts", "graphRagExecution", "GraphRAG 批次尝试次数", "单个批次包含首次调用在内的最大尝试次数。", 1, 10,
                        "次", NEW_BUILD_TASK, o -> o.getGraphRagExecution().getMaxBatchAttempts(),
                        (o, v) -> o.getGraphRagExecution().setMaxBatchAttempts((Integer) v)),
                duration("graphRag.execution.retryBackoffMillis", "graphRagExecution", "GraphRAG 重试退避", "可恢复错误重试前的等待时间。", 0, 60000,
                        NEW_BUILD_TASK, o -> o.getGraphRagExecution().getRetryBackoffMillis(),
                        (o, v) -> o.getGraphRagExecution().setRetryBackoffMillis((Long) v)),
                duration("graphRag.execution.documentBudgetMillis", "graphRagExecution", "GraphRAG 文档预算", "整篇文档 GraphRAG 抽取总时间预算。", 1, 3600000,
                        RESTART_REQUIRED, o -> o.getGraphRagExecution().getDocumentBudgetMillis(),
                        (o, v) -> o.getGraphRagExecution().setDocumentBudgetMillis((Long) v)),
                duration("graphRag.execution.toolBudgetMillis", "graphRagExecution", "GraphRAG 工具预算", "单次 Python GraphRAG 工具调用时间预算。", 1, 300000,
                        RESTART_REQUIRED, o -> o.getGraphRagExecution().getToolBudgetMillis(),
                        (o, v) -> o.getGraphRagExecution().setToolBudgetMillis((Long) v)),
                duration("graphRag.execution.reserveMillis", "graphRagExecution", "GraphRAG 传输预留", "工具预算外为传输及文档级收尾保留的时间。", 1, 120000,
                        RESTART_REQUIRED, o -> o.getGraphRagExecution().getReserveMillis(),
                        (o, v) -> o.getGraphRagExecution().setReserveMillis((Long) v)),
                integer("graphRag.execution.maxSplitDepth", "graphRagExecution", "GraphRAG 细分深度", "批次体积或超时细分的最大递归深度。", 0, 10,
                        "层", NEW_BUILD_TASK, o -> o.getGraphRagExecution().getMaxSplitDepth(),
                        (o, v) -> o.getGraphRagExecution().setMaxSplitDepth((Integer) v)),
                integer("graphRag.execution.maxSplits", "graphRagExecution", "GraphRAG 体积细分次数", "单篇文档体积类细分的总次数上限。", 0, 1024,
                        "次", NEW_BUILD_TASK, o -> o.getGraphRagExecution().getMaxSplits(),
                        (o, v) -> o.getGraphRagExecution().setMaxSplits((Integer) v)),
                integer("graphRag.execution.maxTimeoutSplitsPerSource", "graphRagExecution", "GraphRAG 超时细分次数", "单个来源允许的超时细分次数。", 0, 10,
                        "次", NEW_BUILD_TASK, o -> o.getGraphRagExecution().getMaxTimeoutSplitsPerSource(),
                        (o, v) -> o.getGraphRagExecution().setMaxTimeoutSplitsPerSource((Integer) v)),
                integer("graphRag.execution.maxBatches", "graphRagExecution", "GraphRAG 批次数量上限", "整篇文档允许执行的批次数量上限。", 1, 4096,
                        "批", NEW_BUILD_TASK, o -> o.getGraphRagExecution().getMaxBatches(),
                        (o, v) -> o.getGraphRagExecution().setMaxBatches((Integer) v)),
                longInteger("graphRag.execution.maxCandidateBytes", "graphRagExecution", "GraphRAG 候选字节上限", "抽取过程暂存候选的序列化字节上限。", 1, 268435456,
                        "字节", NEW_BUILD_TASK, o -> o.getGraphRagExecution().getMaxCandidateBytes(),
                        (o, v) -> o.getGraphRagExecution().setMaxCandidateBytes((Long) v)),
                integer("graphRag.execution.maxObservationBytes", "graphRagExecution", "GraphRAG 观测字节上限", "单份抽取观测 JSON 的字节上限。", 4096, 32768,
                        "字节", NEW_BUILD_TASK, o -> o.getGraphRagExecution().getMaxObservationBytes(),
                        (o, v) -> o.getGraphRagExecution().setMaxObservationBytes((Integer) v)),

                integer("chunkEnrichment.execution.workerThreads", "chunkEnrichmentExecution", "关键词与问题增强工作线程", "每个 Java 实例关键词与问题增强共享池的固定工作线程数。", 1, 64,
                        "线程", RESTART_REQUIRED, o -> o.getChunkEnrichmentExecution().getWorkerThreads(),
                        (o, v) -> o.getChunkEnrichmentExecution().setWorkerThreads((Integer) v)),
                integer("chunkEnrichment.execution.queueCapacity", "chunkEnrichmentExecution", "关键词与问题增强队列容量", "关键词与问题增强共享池允许排队的批次数量。", 1, 4096,
                        "任务", RESTART_REQUIRED, o -> o.getChunkEnrichmentExecution().getQueueCapacity(),
                        (o, v) -> o.getChunkEnrichmentExecution().setQueueCapacity((Integer) v)),
                integer("chunkEnrichment.execution.documentConcurrency", "chunkEnrichmentExecution", "单文档增强并发", "单篇文档允许同时执行的关键词与问题增强批次数量。", 1, 64,
                        "批", RESTART_REQUIRED, o -> o.getChunkEnrichmentExecution().getDocumentConcurrency(),
                        (o, v) -> o.getChunkEnrichmentExecution().setDocumentConcurrency((Integer) v)),
                duration("chunkEnrichment.execution.batchTimeoutMillis", "chunkEnrichmentExecution", "增强批次超时", "关键词与问题增强单个批次进入工作线程后的本地执行时限。", 1, 300000,
                        RESTART_REQUIRED, o -> o.getChunkEnrichmentExecution().getBatchTimeoutMillis(),
                        (o, v) -> o.getChunkEnrichmentExecution().setBatchTimeoutMillis((Long) v)),
                duration("chunkEnrichment.execution.documentBudgetMillis", "chunkEnrichmentExecution", "增强文档预算", "单篇文档关键词与问题增强阶段的总时间预算。", 1, 3600000,
                        RESTART_REQUIRED, o -> o.getChunkEnrichmentExecution().getDocumentBudgetMillis(),
                        (o, v) -> o.getChunkEnrichmentExecution().setDocumentBudgetMillis((Long) v)),
                duration("chunkEnrichment.execution.pollMillis", "chunkEnrichmentExecution", "增强协调轮询间隔", "关键词与问题增强协调线程检查批次状态的间隔。", 1, 1000,
                        RESTART_REQUIRED, o -> o.getChunkEnrichmentExecution().getPollMillis(),
                        (o, v) -> o.getChunkEnrichmentExecution().setPollMillis((Long) v)),

                integer("ragRuntime.vectorTopK", "retrievalWindow", "向量召回数量", "每个子问题从向量通道最多召回的候选数量。", 1, 200, "条",
                        NEW_CONVERSATION, o -> o.getRagRuntime().getVectorTopK(),
                        (o, v) -> o.getRagRuntime().setVectorTopK((Integer) v)),
                integer("ragRuntime.keywordTopK", "retrievalWindow", "关键词召回数量", "每个子问题从关键词通道最多召回的候选数量。", 1, 200, "条",
                        NEW_CONVERSATION, o -> o.getRagRuntime().getKeywordTopK(),
                        (o, v) -> o.getRagRuntime().setKeywordTopK((Integer) v)),
                integer("ragRuntime.graphRagTopK", "retrievalWindow", "GraphRAG 召回数量", "每个子问题最多保留的 GraphRAG 候选数量。", 1,
                        100, "条", NEW_CONVERSATION, o -> o.getRagRuntime().getGraphRagTopK(),
                        (o, v) -> o.getRagRuntime().setGraphRagTopK((Integer) v)),
                integer("ragRuntime.graphRagMaxHops", "retrievalWindow", "GraphRAG 最大跳数", "关系检索允许扩展的最大图跳数。", 1, 5, "跳",
                        NEW_CONVERSATION, o -> o.getRagRuntime().getGraphRagMaxHops(),
                        (o, v) -> o.getRagRuntime().setGraphRagMaxHops((Integer) v)),
                integer("ragRuntime.raptorTopK", "retrievalWindow", "RAPTOR 摘要召回数量", "每个子问题最多保留的 RAPTOR 摘要数量。", 1, 100,
                        "条", NEW_CONVERSATION, o -> o.getRagRuntime().getRaptorTopK(),
                        (o, v) -> o.getRagRuntime().setRaptorTopK((Integer) v)),
                integer("ragRuntime.raptorSourceChunkTopK", "retrievalWindow", "RAPTOR 来源块数量",
                        "每条 RAPTOR 摘要最多展开的真实来源块数量。", 1, 50, "条", NEW_CONVERSATION,
                        o -> o.getRagRuntime().getRaptorSourceChunkTopK(),
                        (o, v) -> o.getRagRuntime().setRaptorSourceChunkTopK((Integer) v)),
                integer("ragRuntime.candidateTopK", "retrievalWindow", "融合候选窗口", "各检索通道合并后进入候选窗口的最大数量。", 1, 500, "条",
                        NEW_CONVERSATION, o -> o.getRagRuntime().getCandidateTopK(),
                        (o, v) -> o.getRagRuntime().setCandidateTopK((Integer) v)),
                integer("ragRuntime.rerankCandidateTopK", "retrievalWindow", "重排候选窗口", "最多送入重排阶段的候选数量。", 1, 500, "条",
                        NEW_CONVERSATION, o -> o.getRagRuntime().getRerankCandidateTopK(),
                        (o, v) -> o.getRagRuntime().setRerankCandidateTopK((Integer) v)),
                integer("ragRuntime.finalTopK", "retrievalWindow", "最终证据窗口", "最终证据策略最多选择的来源数量。", 1, 50, "条",
                        NEW_CONVERSATION, o -> o.getRagRuntime().getFinalTopK(),
                        (o, v) -> o.getRagRuntime().setFinalTopK((Integer) v)),

                bool("ragRuntime.rerankEnabled", "execution", "启用重排", "决定新会话是否执行统一候选重排。", NEW_CONVERSATION,
                        o -> o.getRagRuntime().isRerankEnabled(),
                        (o, v) -> o.getRagRuntime().setRerankEnabled((Boolean) v)),
                duration("ragRuntime.channelTimeoutMs", "execution", "单通道超时", "单个检索通道超过该时长后按超时结果收口。", 100, 120000,
                        NEW_CONVERSATION, o -> o.getRagRuntime().getChannelTimeoutMs(),
                        (o, v) -> o.getRagRuntime().setChannelTimeoutMs((Long) v)),
                duration("ragRuntime.subQuestionTimeoutMs", "execution", "子问题总超时", "一个子问题的并行检索超过该时长后停止等待。", 100, 180000,
                        NEW_CONVERSATION, o -> o.getRagRuntime().getSubQuestionTimeoutMs(),
                        (o, v) -> o.getRagRuntime().setSubQuestionTimeoutMs((Long) v)),

                percentage("ragRuntime.minVectorSimilarity", "relevance", "向量最低相似度", "低于该比例的向量候选不会进入后续融合。",
                        NEW_CONVERSATION, o -> o.getRagRuntime().getMinVectorSimilarity(),
                        (o, v) -> o.getRagRuntime().setMinVectorSimilarity((Double) v)),
                percentage("ragRuntime.keywordRelativeScoreFloor", "relevance", "关键词相对分数下限", "关键词候选相对本通道最高分的最低保留比例。",
                        NEW_CONVERSATION, o -> o.getRagRuntime().getKeywordRelativeScoreFloor(),
                        (o, v) -> o.getRagRuntime().setKeywordRelativeScoreFloor((Double) v)),

                bool("ragRuntime.keywordChannelEnabled", "channels", "启用关键词通道", "决定新会话是否并行执行关键词检索。", NEW_CONVERSATION,
                        o -> o.getRagRuntime().isKeywordChannelEnabled(),
                        (o, v) -> o.getRagRuntime().setKeywordChannelEnabled((Boolean) v)),
                bool("ragRuntime.tableChannelEnabled", "channels", "启用表格通道", "决定新会话是否检索结构化表格证据。", NEW_CONVERSATION,
                        o -> o.getRagRuntime().isTableChannelEnabled(),
                        (o, v) -> o.getRagRuntime().setTableChannelEnabled((Boolean) v)),
                bool("ragRuntime.graphRagChannelEnabled", "channels", "启用 GraphRAG 通道", "决定新会话是否检索知识图谱证据。",
                        NEW_CONVERSATION, o -> o.getRagRuntime().isGraphRagChannelEnabled(),
                        (o, v) -> o.getRagRuntime().setGraphRagChannelEnabled((Boolean) v)),
                bool("ragRuntime.raptorChannelEnabled", "channels", "启用 RAPTOR 通道", "决定新会话是否检索层级摘要证据。",
                        NEW_CONVERSATION, o -> o.getRagRuntime().isRaptorChannelEnabled(),
                        (o, v) -> o.getRagRuntime().setRaptorChannelEnabled((Boolean) v)),

                decimal("ragRuntime.hybrid.vectorWeight", "fusion", "向量通道权重", "融合排序时向量通道分数的权重。", 0, 5, 0.05, "倍",
                        NEW_CONVERSATION, o -> o.getRagRuntime().getHybrid().getVectorWeight(),
                        (o, v) -> o.getRagRuntime().getHybrid().setVectorWeight((Double) v)),
                decimal("ragRuntime.hybrid.keywordWeight", "fusion", "关键词通道权重", "融合排序时关键词通道分数的权重。", 0, 5, 0.05, "倍",
                        NEW_CONVERSATION, o -> o.getRagRuntime().getHybrid().getKeywordWeight(),
                        (o, v) -> o.getRagRuntime().getHybrid().setKeywordWeight((Double) v)),
                decimal("ragRuntime.hybrid.tableWeight", "fusion", "表格通道权重", "融合排序时表格通道分数的权重。", 0, 5, 0.05, "倍",
                        NEW_CONVERSATION, o -> o.getRagRuntime().getHybrid().getTableWeight(),
                        (o, v) -> o.getRagRuntime().getHybrid().setTableWeight((Double) v)),
                decimal("ragRuntime.hybrid.graphRagWeight", "fusion", "GraphRAG 通道权重", "融合排序时 GraphRAG 通道分数的权重。", 0, 5,
                        0.05, "倍", NEW_CONVERSATION, o -> o.getRagRuntime().getHybrid().getGraphRagWeight(),
                        (o, v) -> o.getRagRuntime().getHybrid().setGraphRagWeight((Double) v)),
                decimal("ragRuntime.hybrid.raptorWeight", "fusion", "RAPTOR 通道权重", "融合排序时 RAPTOR 通道分数的权重。", 0, 5, 0.05,
                        "倍", NEW_CONVERSATION, o -> o.getRagRuntime().getHybrid().getRaptorWeight(),
                        (o, v) -> o.getRagRuntime().getHybrid().setRaptorWeight((Double) v)),
                decimal("ragRuntime.hybrid.rankWeight", "fusion", "排名特征权重", "融合排序时通道内排名特征的权重。", 0, 5, 0.05, "倍",
                        NEW_CONVERSATION, o -> o.getRagRuntime().getHybrid().getRankWeight(),
                        (o, v) -> o.getRagRuntime().getHybrid().setRankWeight((Double) v)),
                decimal("ragRuntime.hybrid.originalScoreWeight", "fusion", "原始分数权重", "融合排序时保留原始通道分数的附加权重。", 0, 1, 0.01,
                        "倍", NEW_CONVERSATION, o -> o.getRagRuntime().getHybrid().getOriginalScoreWeight(),
                        (o, v) -> o.getRagRuntime().getHybrid().setOriginalScoreWeight((Double) v)),
                decimal("ragRuntime.hybrid.metadataBoostWeight", "fusion", "元数据加分权重", "标题、章节和关键词等元数据特征的加分权重。", 0, 1,
                        0.01, "倍", NEW_CONVERSATION, o -> o.getRagRuntime().getHybrid().getMetadataBoostWeight(),
                        (o, v) -> o.getRagRuntime().getHybrid().setMetadataBoostWeight((Double) v)),
                decimal("ragRuntime.hybrid.maxMetadataBoost", "fusion", "元数据最大加分", "单条候选允许获得的元数据加分上限。", 0, 5, 0.05, "倍",
                        NEW_CONVERSATION, o -> o.getRagRuntime().getHybrid().getMaxMetadataBoost(),
                        (o, v) -> o.getRagRuntime().getHybrid().setMaxMetadataBoost((Double) v)),

                bool("chat.recommendationEnabled", "chatAgent", "启用推荐追问", "主回答完成后生成最多 3 个可继续追问的问题。", NEW_CONVERSATION,
                        o -> o.getChat().isRecommendationEnabled(),
                        (o, v) -> o.getChat().setRecommendationEnabled((Boolean) v)),
                integer("chat.maxModelCallsPerRun", "chatAgent", "单次模型调用上限", "单次对话运行允许的最大模型调用次数。", 1, 100, "次",
                        RESTART_REQUIRED, o -> o.getChat().getMaxModelCallsPerRun(),
                        (o, v) -> o.getChat().setMaxModelCallsPerRun((Integer) v)),
                integer("chat.maxModelCallsPerThread", "chatAgent", "会话模型调用上限", "同一会话线程累计允许的最大模型调用次数。", 1, 10000, "次",
                        RESTART_REQUIRED, o -> o.getChat().getMaxModelCallsPerThread(),
                        (o, v) -> o.getChat().setMaxModelCallsPerThread((Integer) v)),
                integer("chat.maxToolCallsPerRun", "chatAgent", "单次工具调用上限", "单次对话运行允许的最大外部工具调用次数。", 1, 100, "次",
                        RESTART_REQUIRED, o -> o.getChat().getMaxToolCallsPerRun(),
                        (o, v) -> o.getChat().setMaxToolCallsPerRun((Integer) v)),
                integer("chat.maxToolCallsPerThread", "chatAgent", "会话工具调用上限", "同一会话线程累计允许的最大外部工具调用次数。", 1, 10000, "次",
                        RESTART_REQUIRED, o -> o.getChat().getMaxToolCallsPerThread(),
                        (o, v) -> o.getChat().setMaxToolCallsPerThread((Integer) v)),
                integer("chat.historyPreviewTurns", "chatAgent", "推荐问题历史轮数", "生成推荐问题时最多回看的最近历史问答轮数。", 0, 50, "轮",
                        NEW_CONVERSATION, o -> o.getChat().getHistoryPreviewTurns(),
                        (o, v) -> o.getChat().setHistoryPreviewTurns((Integer) v)),
                duration("chat.recommendationTimeoutMs", "chatAgent", "推荐问题超时", "推荐问题生成超过该时长后按无推荐结果收口。", 100, 120000,
                        NEW_CONVERSATION, o -> o.getChat().getRecommendationTimeoutMs(),
                        (o, v) -> o.getChat().setRecommendationTimeoutMs((Long) v)),

                bool("rag.enabled", "ragOrchestration", "启用 RAG 前置编排", "进入最终执行器前执行历史加载、问题改写和文档检索规划。", NEW_CONVERSATION,
                        o -> o.getRag().isEnabled(), (o, v) -> o.getRag().setEnabled((Boolean) v)),
                bool("rag.rewriteEnabled", "ragOrchestration", "启用问题改写", "对多轮问题做独立化改写并按需拆分子问题。", NEW_CONVERSATION,
                        o -> o.getRag().isRewriteEnabled(), (o, v) -> o.getRag().setRewriteEnabled((Boolean) v)),
                integer("rag.rewriteHistoryTurns", "ragOrchestration", "改写历史轮数", "问题改写阶段最多回看的历史问答轮数。", 0, 50, "轮",
                        NEW_CONVERSATION, o -> o.getRag().getRewriteHistoryTurns(),
                        (o, v) -> o.getRag().setRewriteHistoryTurns((Integer) v)),
                bool("rag.rewriteOptions.enabled", "ragOrchestration", "启用改写模型参数覆盖", "为问题改写阶段单独覆盖模型采样参数。",
                        NEW_CONVERSATION, o -> o.getRag().getRewriteOptions().isEnabled(),
                        (o, v) -> o.getRag().getRewriteOptions().setEnabled((Boolean) v)),
                decimal("rag.rewriteOptions.temperature", "ragOrchestration", "改写温度", "改写阶段的采样温度，越低越保守。", 0, 2, 0.05,
                        "", NEW_CONVERSATION, o -> o.getRag().getRewriteOptions().getTemperature(),
                        (o, v) -> o.getRag().getRewriteOptions().setTemperature((Double) v)),
                percentage("rag.rewriteOptions.topP", "ragOrchestration", "改写 Top P", "改写阶段的核采样比例，越低越不易发散。",
                        NEW_CONVERSATION, o -> o.getRag().getRewriteOptions().getTopP(),
                        (o, v) -> o.getRag().getRewriteOptions().setTopP((Double) v)),
                bool("rag.rewriteOptions.thinking", "ragOrchestration", "启用改写思考模式", "通过 OpenAI 兼容协议为改写调用透传 thinking。",
                        NEW_CONVERSATION, o -> o.getRag().getRewriteOptions().getThinking(),
                        (o, v) -> o.getRag().getRewriteOptions().setThinking((Boolean) v)),
                integer("rag.maxSubQuestions", "ragOrchestration", "子问题拆分上限", "单轮最多拆分的子问题数量。", 1, 20, "个",
                        NEW_CONVERSATION, o -> o.getRag().getMaxSubQuestions(),
                        (o, v) -> o.getRag().setMaxSubQuestions((Integer) v)),
                bool("rag.graphRagQueryPlanEnabled", "ragOrchestration", "启用 GraphRAG 查询计划",
                        "允许 LLM 提供受控的实体、关系、社区和跳数建议。", NEW_CONVERSATION,
                        o -> o.getRag().getGraphRagQueryPlan().isEnabled(),
                        (o, v) -> o.getRag().getGraphRagQueryPlan().setEnabled((Boolean) v)),
                percentage("rag.autoRouteRecommendationThreshold", "ragOrchestration", "文档推荐阈值",
                        "只控制可降级的 top 文档推荐提示，不改变知识范围。", NEW_CONVERSATION,
                        o -> o.getRag().getAutoRoute().getRecommendationThreshold(),
                        (o, v) -> o.getRag().getAutoRoute().setRecommendationThreshold((Double) v)),

                integer("rag.parentEvidenceMaxChars", "ragContext", "单个父块证据预算", "回答阶段单个父块正文与命中子片段摘要的字符预算。", 100, 50000,
                        "字符", NEW_CONVERSATION, o -> o.getRag().getParentEvidenceMaxChars(),
                        (o, v) -> o.getRag().setParentEvidenceMaxChars((Integer) v)),
                integer("rag.planningHistoryMaxChars", "ragContext", "规划历史预算", "改写和检索规划阶段可使用的历史上下文字符数。", 100, 50000,
                        "字符", NEW_CONVERSATION, o -> o.getRag().getPlanningHistoryMaxChars(),
                        (o, v) -> o.getRag().setPlanningHistoryMaxChars((Integer) v)),
                integer("rag.answerHistoryMaxChars", "ragContext", "回答历史预算", "回答阶段可使用的最近问题和结构化历史字符数。", 100, 50000, "字符",
                        NEW_CONVERSATION, o -> o.getRag().getAnswerHistoryMaxChars(),
                        (o, v) -> o.getRag().setAnswerHistoryMaxChars((Integer) v)),
                integer("rag.totalEvidenceMaxChars", "ragContext", "总证据预算", "回答阶段全部证据允许占用的总字符数。", 100, 200000, "字符",
                        NEW_CONVERSATION, o -> o.getRag().getTotalEvidenceMaxChars(),
                        (o, v) -> o.getRag().setTotalEvidenceMaxChars((Integer) v)),
                integer("rag.perSubQuestionEvidenceMaxChars", "ragContext", "单子问题证据预算", "每个子问题允许占用的证据字符数。", 100, 100000,
                        "字符", NEW_CONVERSATION, o -> o.getRag().getPerSubQuestionEvidenceMaxChars(),
                        (o, v) -> o.getRag().setPerSubQuestionEvidenceMaxChars((Integer) v)),
                text("rag.noEvidenceReply", "ragContext", "无证据兜底文案", "知识问题没有合格证据时直接返回的文案。", 2000, NEW_CONVERSATION,
                        o -> o.getRag().getNoEvidenceReply(), (o, v) -> o.getRag().setNoEvidenceReply((String) v)),
                bool("rag.historySummary.enabled", "ragContext", "启用长期会话摘要", "使用长期摘要与最近原文窗口压缩长会话。", NEW_CONVERSATION,
                        o -> o.getRag().getHistorySummary().isEnabled(),
                        (o, v) -> o.getRag().getHistorySummary().setEnabled((Boolean) v)),
                integer("rag.historySummary.keepRecentTurns", "ragContext", "保留最近原文轮数", "始终直接保留、不进入长期摘要的最近问答轮数。", 0, 50,
                        "轮", NEW_CONVERSATION, o -> o.getRag().getHistorySummary().getKeepRecentTurns(),
                        (o, v) -> o.getRag().getHistorySummary().setKeepRecentTurns((Integer) v)),
                integer("rag.historySummary.compressionBatchTurns", "ragContext", "增量压缩批次", "单次增量压缩最多推进的历史问答轮数。", 1, 50,
                        "轮", NEW_CONVERSATION, o -> o.getRag().getHistorySummary().getCompressionBatchTurns(),
                        (o, v) -> o.getRag().getHistorySummary().setCompressionBatchTurns((Integer) v)),
                integer("rag.historySummary.recentTranscriptMaxChars", "ragContext", "最近原文窗口预算", "最近原文问答窗口允许占用的最大字符数。",
                        100, 50000, "字符", NEW_CONVERSATION,
                        o -> o.getRag().getHistorySummary().getRecentTranscriptMaxChars(),
                        (o, v) -> o.getRag().getHistorySummary().setRecentTranscriptMaxChars((Integer) v)),
                integer("rag.historySummary.summaryMaxChars", "ragContext", "长期摘要预算", "长期会话摘要允许占用的最大字符数。", 100, 50000,
                        "字符", NEW_CONVERSATION, o -> o.getRag().getHistorySummary().getSummaryMaxChars(),
                        (o, v) -> o.getRag().getHistorySummary().setSummaryMaxChars((Integer) v)),

                integer("rag.raptorMaxClusterSize", "raptorBuild", "RAPTOR 簇节点上限", "每个摘要簇最多包含的下层节点数量。", 2, 50, "个",
                        NEW_BUILD_TASK, o -> o.getRag().getRaptorMaxClusterSize(),
                        (o, v) -> o.getRag().setRaptorMaxClusterSize((Integer) v)),
                integer("rag.raptorMaxLevels", "raptorBuild", "RAPTOR 摘要层数", "摘要树最多生成的层数。", 1, 8, "层", NEW_BUILD_TASK,
                        o -> o.getRag().getRaptorMaxLevels(), (o, v) -> o.getRag().setRaptorMaxLevels((Integer) v)),
                bool("rag.raptorLlmSummaryEnabled", "raptorBuild", "启用 RAPTOR LLM 摘要",
                        "允许 Python rag-tools 在构建阶段尝试模型摘要。", NEW_BUILD_TASK, o -> o.getRag().isRaptorLlmSummaryEnabled(),
                        (o, v) -> o.getRag().setRaptorLlmSummaryEnabled((Boolean) v)),
                percentage("rag.raptorSummaryQualityFloor", "raptorBuild", "RAPTOR 质量下限", "低于该质量比例的摘要节点不会入库或建立索引。",
                        NEW_BUILD_TASK, o -> o.getRag().getRaptorSummaryQualityFloor(),
                        (o, v) -> o.getRag().setRaptorSummaryQualityFloor((Double) v)),
                integer("raptor.execution.llmConcurrency", "raptorBuild", "RAPTOR 每层模型并发",
                        "单次 RAPTOR 构建每层摘要簇使用的模型并发数。", 1, 20, "并发", NEW_BUILD_TASK,
                        o -> o.getRaptorLlmConcurrency(), (o, v) -> o.setRaptorLlmConcurrency((Integer) v)),

                bool("graphRag.build.leaseEnabled", "graphRagBuild", "启用 GraphRAG 构建租约",
                        "关闭后 GraphRAG 构建不能发布结果，仅用于故障隔离。", NEW_BUILD_TASK, o -> o.getGraphRagBuild().isLeaseEnabled(),
                        (o, v) -> o.getGraphRagBuild().setLeaseEnabled((Boolean) v)),
                integer("graphRag.build.leaseTtlSeconds", "graphRagBuild", "构建租约时长", "GraphRAG 构建任务持有租约的最长时间。", 30,
                        86400, "秒", NEW_BUILD_TASK, o -> o.getGraphRagBuild().getLeaseTtlSeconds(),
                        (o, v) -> o.getGraphRagBuild().setLeaseTtlSeconds((Integer) v)),
                integer("graphRag.build.maxAttempts", "graphRagBuild", "构建尝试次数", "GraphRAG 构建发生可重试失败时允许的最大尝试次数。", 1, 10,
                        "次", NEW_BUILD_TASK, o -> o.getGraphRagBuild().getMaxAttempts(),
                        (o, v) -> o.getGraphRagBuild().setMaxAttempts((Integer) v)),
                duration("graphRag.build.retryBackoffMillis", "graphRagBuild", "构建重试退避", "GraphRAG 构建重试前的等待时间。", 0,
                        60000, NEW_BUILD_TASK, o -> o.getGraphRagBuild().getRetryBackoffMillis(),
                        (o, v) -> o.getGraphRagBuild().setRetryBackoffMillis((Long) v)),

                integer("graphRag.extraction.batchChunkLimit", "graphRagEnhancement", "抽取批次块数", "单次统一候选抽取最多包含的来源段数。", 1,
                        20, "块", NEW_BUILD_TASK, o -> o.getGraphRagExtraction().getBatchChunkLimit(),
                        (o, v) -> o.getGraphRagExtraction().setBatchChunkLimit((Integer) v)),
                integer("graphRag.extraction.inputTokenBudget", "graphRagEnhancement", "抽取输入 Token 预算",
                        "实际 Prompt 与序列化内容的保守 Token 估算上限；输出预留和安全余量另计。", 256, 32000, "token", NEW_BUILD_TASK,
                        o -> o.getGraphRagExtraction().getInputTokenBudget(),
                        (o, v) -> o.getGraphRagExtraction().setInputTokenBudget((Integer) v)),
                integer("graphRag.model.modelContextTokens", "graphRagEnhancement", "GraphRAG 模型上下文容量",
                        "Java 随请求发送的 GraphRAG 工具模型上下文声明。", 1024, 200000, "token", NEW_BUILD_TASK,
                        o -> o.getGraphRagExtraction().getModelContextTokens(),
                        (o, v) -> o.getGraphRagExtraction().setModelContextTokens((Integer) v)),
                integer("graphRag.model.outputReserve", "graphRagEnhancement", "GraphRAG 输出 Token 预留",
                        "Java 随请求发送的模型输出上限。", 1, 100000, "token", NEW_BUILD_TASK,
                        o -> o.getGraphRagExtraction().getOutputReserve(),
                        (o, v) -> o.getGraphRagExtraction().setOutputReserve((Integer) v)),
                integer("graphRag.model.promptReserve", "graphRagEnhancement", "GraphRAG Prompt 安全余量",
                        "Java 随请求发送的 Prompt 估算安全余量。", 1, 100000, "token", NEW_BUILD_TASK,
                        o -> o.getGraphRagExtraction().getPromptReserve(),
                        (o, v) -> o.getGraphRagExtraction().setPromptReserve((Integer) v)),
                integer("graphRag.model.modelResponseMaxBytes", "graphRagEnhancement", "模型响应字节上限",
                        "Python 接收供应商模型响应的字节上限。", 1, 67108864, "字节", NEW_BUILD_TASK,
                        o -> o.getGraphRagExtraction().getModelResponseMaxBytes(),
                        (o, v) -> o.getGraphRagExtraction().setModelResponseMaxBytes((Integer) v)),
                integer("graphRag.extraction.maxEntityNameChars", "graphRagEnhancement", "实体名称长度上限",
                        "GraphRAG 候选实体名称的 Unicode 码点上限。", 1, 10000, "字符", NEW_BUILD_TASK,
                        o -> o.getGraphRagExtraction().getMaxEntityNameChars(),
                        (o, v) -> o.getGraphRagExtraction().setMaxEntityNameChars((Integer) v)),
                integer("graphRag.execution.maxDocumentBytes", "graphRagBuild", "来源文档字节上限",
                        "Java 随请求发送的完整来源 UTF-8 字节上限。", 1, 64000000, "字节", NEW_BUILD_TASK,
                        o -> (int) o.getGraphRagExtraction().getMaxDocumentBytes(),
                        (o, v) -> o.getGraphRagExtraction().setMaxDocumentBytes(((Integer) v).longValue())),
                integer("graphRag.extraction.maxQuoteChars", "graphRagEnhancement", "抽取引用长度", "单条受控抽取原文引用允许的最大字符数。", 20,
                        2000, "字符", NEW_BUILD_TASK, o -> o.getGraphRagExtraction().getMaxQuoteChars(),
                        (o, v) -> o.getGraphRagExtraction().setMaxQuoteChars((Integer) v)),
                integer("graphRag.extraction.maxReasonChars", "graphRagEnhancement", "抽取理由长度",
                        "单条候选说明允许的最大 Unicode 码点数；超限整批失败。", 20, 2000, "字符", NEW_BUILD_TASK,
                        o -> o.getGraphRagExtraction().getMaxReasonChars(),
                        (o, v) -> o.getGraphRagExtraction().setMaxReasonChars((Integer) v)),
                integer("graphRag.extraction.maxUnitTextChars", "graphRagEnhancement", "抽取单元文本长度",
                        "统一候选分段的最大 Unicode 码点数；连续细分保留全部尾部。", 50, 8000, "字符", NEW_BUILD_TASK,
                        o -> o.getGraphRagExtraction().getMaxUnitTextChars(),
                        (o, v) -> o.getGraphRagExtraction().setMaxUnitTextChars((Integer) v)),
                bool("graphRag.communityReport.enabled", "graphRagEnhancement", "启用社区报告增强",
                        "允许 LLM 基于白名单实体、关系和证据生成社区报告建议。", NEW_BUILD_TASK,
                        o -> o.getGraphRagCommunityReport().isEnabled(),
                        (o, v) -> o.getGraphRagCommunityReport().setEnabled((Boolean) v)),
                bool("graphRag.entityResolution.enabled", "graphRagEnhancement", "启用实体消歧增强", "允许 LLM 基于受控候选提供实体合并建议。",
                        NEW_BUILD_TASK, o -> o.getGraphRagEntityResolution().isEnabled(),
                        (o, v) -> o.getGraphRagEntityResolution().setEnabled((Boolean) v)),

                bool("chunkEnrichment.enabled", "chunkEnrichment", "启用切块智能增强", "新构建任务使用 LLM 生成受控关键词和推荐问题。",
                        NEW_BUILD_TASK, o -> o.getChunkEnrichment().isEnabled(),
                        (o, v) -> o.getChunkEnrichment().setEnabled((Boolean) v)),
                integer("chunkEnrichment.maxKeywords", "chunkEnrichment", "关键词数量上限", "每个 chunk 最多保留的智能关键词数。", 1, 50,
                        "个", NEW_BUILD_TASK, o -> o.getChunkEnrichment().getMaxKeywords(),
                        (o, v) -> o.getChunkEnrichment().setMaxKeywords((Integer) v)),
                integer("chunkEnrichment.maxQuestions", "chunkEnrichment", "推荐问题数量上限", "每个 chunk 最多保留的智能问题数。", 1, 20,
                        "个", NEW_BUILD_TASK, o -> o.getChunkEnrichment().getMaxQuestions(),
                        (o, v) -> o.getChunkEnrichment().setMaxQuestions((Integer) v)),
                integer("chunkEnrichment.maxKeywordChars", "chunkEnrichment", "关键词长度上限", "单个智能关键词允许的最大字符数。", 2, 200,
                        "字符", NEW_BUILD_TASK, o -> o.getChunkEnrichment().getMaxKeywordChars(),
                        (o, v) -> o.getChunkEnrichment().setMaxKeywordChars((Integer) v)),
                integer("chunkEnrichment.maxQuestionChars", "chunkEnrichment", "推荐问题长度上限", "单个智能问题允许的最大字符数。", 2, 500,
                        "字符", NEW_BUILD_TASK, o -> o.getChunkEnrichment().getMaxQuestionChars(),
                        (o, v) -> o.getChunkEnrichment().setMaxQuestionChars((Integer) v)),
                integer("chunkEnrichment.batchChunkLimit", "chunkEnrichment", "增强批次块数", "单次关键词与问题增强调用最多包含的 chunk 数。", 1,
                        50, "块", NEW_BUILD_TASK, o -> o.getChunkEnrichment().getBatchChunkLimit(),
                        (o, v) -> o.getChunkEnrichment().setBatchChunkLimit((Integer) v)),
                integer("chunkEnrichment.batchTokenLimit", "chunkEnrichment", "增强批次 Token 预算",
                        "单次关键词与问题增强调用的估算 token 上限。", 256, 32000, "token", NEW_BUILD_TASK,
                        o -> o.getChunkEnrichment().getBatchTokenLimit(),
                        (o, v) -> o.getChunkEnrichment().setBatchTokenLimit((Integer) v)),

                integer("indexBuild.embeddingBatchSize", "indexBuild", "向量化批次大小",
                        "单次向量化请求包含的文本块数量，当前模型上限为 10。", 1, 10, "块", NEW_BUILD_TASK,
                        o -> o.getIndexBuild().getEmbeddingBatchSize(),
                        (o, v) -> o.getIndexBuild().setEmbeddingBatchSize((Integer) v)),
                integer("indexBuild.embeddingParallelism", "indexBuild", "向量化并发数", "索引构建阶段并发执行的向量化批次数。",
                        1, 16, "批", NEW_BUILD_TASK, o -> o.getIndexBuild().getEmbeddingParallelism(),
                        (o, v) -> o.getIndexBuild().setEmbeddingParallelism((Integer) v)),
                integer("indexBuild.embeddingBatchMaxAttempts", "indexBuild", "向量化尝试次数",
                        "单个向量化批次遇到瞬时网络失败时允许的最大尝试次数。", 1, 10, "次", NEW_BUILD_TASK,
                        o -> o.getIndexBuild().getEmbeddingBatchMaxAttempts(),
                        (o, v) -> o.getIndexBuild().setEmbeddingBatchMaxAttempts((Integer) v)),
                duration("indexBuild.embeddingBatchRetryBackoffMillis", "indexBuild", "向量化重试等待",
                        "向量化批次发生瞬时失败后再次尝试前的等待时间。", 0, 60000, NEW_BUILD_TASK,
                        o -> o.getIndexBuild().getEmbeddingBatchRetryBackoffMillis(),
                        (o, v) -> o.getIndexBuild().setEmbeddingBatchRetryBackoffMillis((Long) v)),
                bool("indexBuild.elasticsearchRefreshWait", "indexBuild", "等待 Elasticsearch 刷新",
                        "构建写入后等待 refresh，使完成状态后的查询立即可见。", NEW_BUILD_TASK,
                        o -> o.getIndexBuild().getElasticsearchRefreshWait(),
                        (o, v) -> o.getIndexBuild().setElasticsearchRefreshWait((Boolean) v)),
                integer("indexBuild.progressLogLimit", "indexBuild", "进度日志返回数量", "前端轻量轮询接口单次返回的最新日志数量。", 1, 500, "条",
                        NEW_BUILD_TASK, o -> o.getIndexBuild().getProgressLogLimit(),
                        (o, v) -> o.getIndexBuild().setProgressLogLimit((Integer) v)),
                integer("indexBuild.executorPoolSize", "indexBuild", "构建线程池大小", "后台索引构建执行器的固定工作线程数。", 1, 32, "线程",
                        RESTART_REQUIRED, o -> o.getIndexBuild().getExecutorPoolSize(),
                        (o, v) -> o.getIndexBuild().setExecutorPoolSize((Integer) v)),
                integer("indexBuild.executorQueueCapacity", "indexBuild", "构建队列容量", "后台索引构建执行器允许排队的任务数量。", 0, 10000,
                        "任务", RESTART_REQUIRED, o -> o.getIndexBuild().getExecutorQueueCapacity(),
                        (o, v) -> o.getIndexBuild().setExecutorQueueCapacity((Integer) v)),

                integer("chunk.recursiveMaxChars", "chunking", "递归子块最大长度", "递归分块时单个子块允许的最大字符数。", 100, 8000, "字符",
                        NEW_BUILD_TASK, o -> o.getChunk().getRecursiveMaxChars(),
                        (o, v) -> o.getChunk().setRecursiveMaxChars((Integer) v)),
                integer("chunk.recursiveOverlapChars", "chunking", "递归子块重叠长度", "递归分块时相邻子块保留的重叠字符数。", 0, 7999, "字符",
                        NEW_BUILD_TASK, o -> o.getChunk().getRecursiveOverlapChars(),
                        (o, v) -> o.getChunk().setRecursiveOverlapChars((Integer) v)),
                integer("chunk.semanticMaxChars", "chunking", "语义子块最大长度", "语义分块时目标子块的最大字符数。", 100, 8000, "字符",
                        NEW_BUILD_TASK, o -> o.getChunk().getSemanticMaxChars(),
                        (o, v) -> o.getChunk().setSemanticMaxChars((Integer) v)),
                integer("chunk.semanticMinChars", "chunking", "语义子块最小长度", "语义分块时目标子块的最小字符数。", 80, 8000, "字符",
                        NEW_BUILD_TASK, o -> o.getChunk().getSemanticMinChars(),
                        (o, v) -> o.getChunk().setSemanticMinChars((Integer) v)),
                percentage("chunk.semanticSimilarityThreshold", "chunking", "语义合并阈值", "判断相邻文本是否继续合并为同一 chunk 的相似度比例。",
                        NEW_BUILD_TASK, o -> o.getChunk().getSemanticSimilarityThreshold(),
                        (o, v) -> o.getChunk().setSemanticSimilarityThreshold((Double) v)),
                integer("chunk.parentBlockMaxChars", "chunking", "父块最大长度", "回答阶段主要上下文父块允许的最大字符数。", 300, 20000, "字符",
                        NEW_BUILD_TASK, o -> o.getChunk().getParentBlockMaxChars(),
                        (o, v) -> o.getChunk().setParentBlockMaxChars((Integer) v)),
                integer("chunk.parentBlockOverlapChars", "chunking", "父块重叠长度", "父块递归裁切时保留的重叠字符数。", 0, 19999, "字符",
                        NEW_BUILD_TASK, o -> o.getChunk().getParentBlockOverlapChars(),
                        (o, v) -> o.getChunk().setParentBlockOverlapChars((Integer) v)),
                integer("chunk.parentSemanticMaxChars", "chunking", "父块语义最大长度", "父块语义切分时的最大字符数。", 300, 20000, "字符",
                        NEW_BUILD_TASK, o -> o.getChunk().getParentSemanticMaxChars(),
                        (o, v) -> o.getChunk().setParentSemanticMaxChars((Integer) v)),
                integer("chunk.parentSemanticMinChars", "chunking", "父块语义最小长度", "父块语义切分时的最小字符数。", 120, 20000, "字符",
                        NEW_BUILD_TASK, o -> o.getChunk().getParentSemanticMinChars(),
                        (o, v) -> o.getChunk().setParentSemanticMinChars((Integer) v)),
                bool("chunk.llmEnabled", "chunking", "启用大模型智能切块", "新构建任务允许使用大模型辅助切块。", NEW_BUILD_TASK,
                        o -> o.getChunk().getLlmEnabled(), (o, v) -> o.getChunk().setLlmEnabled((Boolean) v)),
                integer("chunk.llmMaxChars", "chunking", "智能切块输入长度", "单次送给智能切块模型的最大字符数。", 300, 50000, "字符",
                        NEW_BUILD_TASK, o -> o.getChunk().getLlmMaxChars(),
                        (o, v) -> o.getChunk().setLlmMaxChars((Integer) v)),
                bool("chunk.recommendLlmWhenLowQuality", "chunking", "低质量时推荐智能切块", "文档质量较差时向用户推荐追加大模型智能切块。",
                        NEW_BUILD_TASK, o -> o.getChunk().getRecommendLlmWhenLowQuality(),
                        (o, v) -> o.getChunk().setRecommendLlmWhenLowQuality((Boolean) v)));
        LinkedHashMap<String, ConfigDefinition> rawByKey = new LinkedHashMap<>();
        rawDefinitions.forEach(definition -> rawByKey.put(definition.key(), definition));
        if (rawByKey.size() != rawDefinitions.size()) {
            throw new IllegalStateException("系统配置注册表存在重复键");
        }
        rawDefinitions.forEach(definition -> definition.relatedConfigKeys().forEach(relatedKey -> {
            if (!rawByKey.containsKey(relatedKey)) {
                throw new IllegalStateException("系统配置关联键不存在: " + definition.key() + " -> " + relatedKey);
            }
        }));
        definitions = rawDefinitions.stream()
            .map(definition -> withRelationMetadata(definition, rawByKey))
            .toList();
        LinkedHashMap<String, ConfigDefinition> byKey = new LinkedHashMap<>();
        definitions.forEach(definition -> byKey.put(definition.key(), definition));
        definitionsByKey = Map.copyOf(byKey);
    }

    public List<ConfigDefinition> definitions() {
        return definitions;
    }

    public ConfigDefinition require(String key) {
        ConfigDefinition definition = definitionsByKey.get(key);
        if (definition == null) {
            throw new SystemConfigValidationException("UNSUPPORTED_SYSTEM_CONFIG_KEY",
                    "不支持的配置项: " + key, List.of(key), Map.of(),
                    "只能修改参数目录中已登记且当前版本可识别的配置键。",
                    List.of("刷新参数配置列表并使用页面返回的真实配置键；不要手工提交已删除或拼写错误的键。"));
        }
        return definition;
    }

    public SystemConfigSnapshot apply(SystemConfigSnapshot source, String key, JsonNode value) {
        SystemConfigSnapshot updated = copy(source);
        applyStoredValue(updated, require(key), value);
        validateSnapshot(updated);
        return updated;
    }

    public SystemConfigSnapshot copy(SystemConfigSnapshot source) {
        SystemConfigSnapshot copy = SystemConfigSnapshot.defaults();
        SystemConfigSnapshot actual = source == null ? SystemConfigSnapshot.defaults() : source;
        definitions.forEach(definition -> definition.setter().accept(copy, definition.getter().apply(actual)));
        return copy;
    }

    public void applyStoredValue(SystemConfigSnapshot target, ConfigDefinition definition, JsonNode value) {
        definition.setter().accept(target, parseValue(definition, value));
    }

    public Object read(SystemConfigSnapshot snapshot, ConfigDefinition definition) {
        return definition.getter().apply(snapshot);
    }

    public SystemConfigSnapshot useEffectiveModeValues(SystemConfigSnapshot latest,
            SystemConfigSnapshot effectiveSource, String effectiveMode) {
        SystemConfigSnapshot effective = copy(latest);
        if (effectiveSource == null) {
            return effective;
        }
        definitions.stream()
            .filter(definition -> effectiveMode.equals(definition.effectiveMode()))
            .forEach(definition -> definition.setter().accept(effective, definition.getter().apply(effectiveSource)));
        return effective;
    }

    public void validateSnapshot(SystemConfigSnapshot snapshot) {
        if (snapshot == null) {
            throw new SystemConfigValidationException("SYSTEM_CONFIG_SNAPSHOT_MISSING",
                    "系统配置快照不能为空。", List.of(), Map.of(),
                    "保存和执行前必须取得完整的系统配置快照。",
                    List.of("检查系统配置初始化或迁移状态，恢复完整快照后再重试。"));
        }
        requireComponent(snapshot.getGraphRagExtraction(), "graphRag.extraction");
        requireComponent(snapshot.getRagRuntime(), "ragRuntime");
        requireComponent(snapshot.getChat(), "chat");
        requireComponent(snapshot.getRag(), "rag");
        requireComponent(snapshot.getChunk(), "chunk");
        requireComponent(snapshot.getMvcAsync(), "mvcAsync");
        requireComponent(snapshot.getAdminAuth(), "adminAuth");
        requireComponent(snapshot.getPreviewMode(), "previewMode");
        requireComponent(snapshot.getEvaluationSnapshot(), "evaluation.snapshot");
        requireComponent(snapshot.getRetrievalProbe(), "evaluation.retrievalProbe");
        requireComponent(snapshot.getRagTools(), "ragTools");
        requireComponent(snapshot.getGraphRagDiagnostics(), "graphRag.diagnostics");
        requireComponent(snapshot.getGraphRagExecution(), "graphRag.execution");
        requireComponent(snapshot.getChunkEnrichmentExecution(), "chunkEnrichment.execution");
        if (snapshot.getMvcAsync().getCorePoolSize() > snapshot.getMvcAsync().getMaxPoolSize()) {
            throw relationFailure("MVC_ASYNC_POOL_SIZE_CONFLICT",
                "流式响应核心线程数不能大于最大线程数。", snapshot,
                List.of("mvcAsync.corePoolSize", "mvcAsync.maxPoolSize"),
                "流式响应核心线程数必须小于或等于流式响应最大线程数。",
                List.of("降低核心线程数，或在确认资源预算后提高最大线程数。"));
        }
        SystemConfigSnapshot.GraphRagExtractionOptions extraction = snapshot.getGraphRagExtraction();
        if (extraction.getInputTokenBudget() > extraction.getModelContextTokens()
                - extraction.getOutputReserve() - extraction.getPromptReserve()) {
            int availableInput = extraction.getModelContextTokens() - extraction.getOutputReserve()
                    - extraction.getPromptReserve();
            List<String> keys = List.of("graphRag.extraction.inputTokenBudget",
                    "graphRag.model.modelContextTokens", "graphRag.model.outputReserve",
                    "graphRag.model.promptReserve");
            Map<String, Object> values = Map.of(
                    "graphRag.extraction.inputTokenBudget", extraction.getInputTokenBudget(),
                    "graphRag.model.modelContextTokens", extraction.getModelContextTokens(),
                    "graphRag.model.outputReserve", extraction.getOutputReserve(),
                    "graphRag.model.promptReserve", extraction.getPromptReserve());
            throw new SystemConfigValidationException("GRAPH_RAG_INPUT_TOKEN_BUDGET_CONFLICT",
                    "抽取输入 Token 预算不能超过 GraphRAG 模型上下文容量减去 GraphRAG 输出 Token 预留和 GraphRAG Prompt 安全余量。",
                    keys, values,
                    "当前关系：" + extraction.getInputTokenBudget() + " ≤ " + extraction.getModelContextTokens()
                            + " − " + extraction.getOutputReserve() + " − " + extraction.getPromptReserve()
                            + " = " + availableInput + " token。",
                    List.of("缩小输入来源或批次；如需提高预算，请先核对供应商模型容量。"));
        }
        RagRuntimeOptions runtime = snapshot.getRagRuntime();
        if (runtime.getCandidateTopK() < runtime.getRerankCandidateTopK()) {
            throw relationFailure("RAG_CANDIDATE_WINDOW_CONFLICT",
                    "candidateTopK 不能小于 rerankCandidateTopK。", snapshot,
                    List.of("ragRuntime.candidateTopK", "ragRuntime.rerankCandidateTopK"),
                    "候选窗口必须大于或等于重排窗口。",
                    List.of("提高候选窗口或降低重排窗口，并确认不会无意扩大下游排序成本。"));
        }
        if (runtime.getRerankCandidateTopK() < runtime.getFinalTopK()) {
            throw relationFailure("RAG_RERANK_WINDOW_CONFLICT",
                    "rerankCandidateTopK 不能小于 finalTopK。", snapshot,
                    List.of("ragRuntime.rerankCandidateTopK", "ragRuntime.finalTopK"),
                    "重排窗口必须大于或等于最终返回窗口。",
                    List.of("提高重排窗口或降低最终返回窗口。"));
        }
        if (runtime.getRaptorSourceChunkTopK() > runtime.getRaptorTopK()) {
            throw relationFailure("RAPTOR_SOURCE_WINDOW_CONFLICT",
                    "raptorSourceChunkTopK 不能大于 raptorTopK。", snapshot,
                    List.of("ragRuntime.raptorSourceChunkTopK", "ragRuntime.raptorTopK"),
                    "RAPTOR 来源 chunk 数不能大于 RAPTOR 总窗口。",
                    List.of("降低来源 chunk 数或提高 RAPTOR 总窗口。"));
        }
        if (runtime.getChannelTimeoutMs() > runtime.getSubQuestionTimeoutMs()) {
            throw relationFailure("RAG_CHANNEL_TIMEOUT_CONFLICT",
                    "单通道超时不能大于子问题总超时。", snapshot,
                    List.of("ragRuntime.channelTimeoutMs", "ragRuntime.subQuestionTimeoutMs"),
                    "单通道超时必须小于或等于子问题总超时。",
                    List.of("降低单通道超时，或在确认整体延迟预算后提高子问题总超时。"));
        }
        if (snapshot.getChat().getMaxModelCallsPerThread() < snapshot.getChat().getMaxModelCallsPerRun()) {
            throw relationFailure("CHAT_MODEL_CALL_LIMIT_CONFLICT",
                    "会话模型调用上限不能小于单次模型调用上限。", snapshot,
                    List.of("chat.maxModelCallsPerThread", "chat.maxModelCallsPerRun"),
                    "会话累计上限必须大于或等于单次运行上限。",
                    List.of("提高会话累计上限或降低单次运行上限。"));
        }
        if (snapshot.getChat().getMaxToolCallsPerThread() < snapshot.getChat().getMaxToolCallsPerRun()) {
            throw relationFailure("CHAT_TOOL_CALL_LIMIT_CONFLICT",
                    "会话工具调用上限不能小于单次工具调用上限。", snapshot,
                    List.of("chat.maxToolCallsPerThread", "chat.maxToolCallsPerRun"),
                    "会话累计上限必须大于或等于单次运行上限。",
                    List.of("提高会话累计上限或降低单次运行上限。"));
        }
        if (snapshot.getRag().getPerSubQuestionEvidenceMaxChars() > snapshot.getRag().getTotalEvidenceMaxChars()) {
            throw relationFailure("RAG_EVIDENCE_BUDGET_CONFLICT",
                    "单子问题证据预算不能大于总证据预算。", snapshot,
                    List.of("rag.perSubQuestionEvidenceMaxChars", "rag.totalEvidenceMaxChars"),
                    "单子问题证据预算必须小于或等于总证据预算。",
                    List.of("降低单子问题证据预算，或在确认总 Prompt 预算后提高总证据预算。"));
        }
        if (snapshot.getAdminAuth().getTokenSecret() == null || snapshot.getAdminAuth().getTokenSecret().isBlank()
                || snapshot.getAdminAuth().getTokenExpireMinutes() < 1) {
            throw relationFailure("ADMIN_AUTH_CONFIGURATION_INCOMPLETE",
                    "令牌签名配置不完整。", snapshot,
                    List.of("adminAuth.tokenSecret", "adminAuth.tokenExpireMinutes"),
                    "令牌签名密钥和正数有效期都必须存在。",
                    List.of("补齐缺失的认证参数；令牌密钥只显示配置状态，不要粘贴到日志或工单。"));
        }
        if (snapshot.getRagTools().getConnectTimeoutMs() <= 0) {
            throw relationFailure("RAG_TOOLS_CONNECT_TIMEOUT_INVALID",
                    "RAG 工具连接超时必须大于零。", snapshot,
                    List.of("ragTools.connectTimeoutMs"), "连接超时必须是正数。",
                    List.of("设置正数连接超时；连接失败时先检查服务地址和可达性。"));
        }
        if (snapshot.getRagTools().getGraphExtractReadTimeoutMs()
                < snapshot.getGraphRagExecution().getToolBudgetMillis() + snapshot.getGraphRagExecution().getReserveMillis()) {
            throw relationFailure("GRAPH_RAG_READ_TIMEOUT_BUDGET_CONFLICT",
                    "RAG 工具读取超时不满足 GraphRAG 工具预算和传输预留。", snapshot,
                    List.of("ragTools.graphExtractReadTimeoutMs", "graphRag.execution.toolBudgetMillis",
                            "graphRag.execution.reserveMillis"),
                    "GraphRAG 读取超时必须大于或等于工具预算与传输预留之和。",
                    List.of("分别核对工具执行、HTTP 读取和传输预留，不要用单一超时覆盖所有层级。"));
        }
        if (snapshot.getRagTools().getConnectTimeoutMs() > snapshot.getGraphRagExecution().getReserveMillis()) {
            throw relationFailure("GRAPH_RAG_CONNECT_RESERVE_CONFLICT",
                    "RAG 工具连接超时不能大于 GraphRAG 传输预留。", snapshot,
                    List.of("ragTools.connectTimeoutMs", "graphRag.execution.reserveMillis"),
                    "连接超时必须小于或等于 GraphRAG 传输预留。",
                    List.of("先检查服务可达性；仅在确认连接建立确实需要更久时协调调整两项预算。"));
        }
        if (snapshot.getGraphRagExecution().getDocumentConcurrency() > snapshot.getGraphRagExecution().getWorkerThreads()) {
            throw relationFailure("GRAPH_RAG_CONCURRENCY_CONFLICT",
                    "GraphRAG 文档并发不能大于工作线程。", snapshot,
                    List.of("graphRag.execution.documentConcurrency", "graphRag.execution.workerThreads"),
                    "GraphRAG 文档并发必须小于或等于工作线程。",
                    List.of("降低单文档并发，或评估实例资源后提高工作线程；调整线程数需要重启。"));
        }
        if (snapshot.getGraphRagExecution().getDocumentBudgetMillis()
                <= snapshot.getGraphRagExecution().getReserveMillis()) {
            throw relationFailure("GRAPH_RAG_DOCUMENT_BUDGET_CONFLICT",
                    "GraphRAG 文档预算必须大于传输预留。", snapshot,
                    List.of("graphRag.execution.documentBudgetMillis", "graphRag.execution.reserveMillis"),
                    "GraphRAG 文档总预算必须严格大于传输预留。",
                    List.of("提高文档总预算或降低传输预留，并保留实际工具执行时间。"));
        }
        if (snapshot.getChunkEnrichmentExecution().getDocumentConcurrency()
                > snapshot.getChunkEnrichmentExecution().getWorkerThreads()) {
            throw relationFailure("CHUNK_ENRICHMENT_CONCURRENCY_CONFLICT",
                    "关键词与问题增强文档并发不能大于工作线程。", snapshot,
                    List.of("chunkEnrichment.execution.documentConcurrency",
                            "chunkEnrichment.execution.workerThreads"),
                    "单文档增强并发必须小于或等于增强工作线程。",
                    List.of("降低单文档增强并发，或评估实例资源后提高工作线程；调整线程数需要重启。"));
        }
        if (snapshot.getChunkEnrichmentExecution().getDocumentBudgetMillis()
                < snapshot.getChunkEnrichmentExecution().getBatchTimeoutMillis()) {
            throw relationFailure("CHUNK_ENRICHMENT_BUDGET_CONFLICT",
                    "关键词与问题增强文档预算不能小于批次超时。", snapshot,
                    List.of("chunkEnrichment.execution.documentBudgetMillis",
                            "chunkEnrichment.execution.batchTimeoutMillis"),
                    "增强文档总预算必须大于或等于单批超时。",
                    List.of("提高增强文档总预算或降低单批超时。"));
        }
        DocumentManageProperties.Chunk chunk = snapshot.getChunk();
        if (chunk.getRecursiveOverlapChars() >= chunk.getRecursiveMaxChars()) {
            throw relationFailure("RECURSIVE_CHUNK_OVERLAP_CONFLICT",
                    "递归子块重叠长度必须小于最大长度。", snapshot,
                    List.of("chunk.recursiveOverlapChars", "chunk.recursiveMaxChars"),
                    "递归子块重叠长度必须严格小于最大长度。",
                    List.of("降低重叠长度或提高最大长度，避免生成无法前进的切块窗口。"));
        }
        if (chunk.getSemanticMinChars() > chunk.getSemanticMaxChars()) {
            throw relationFailure("SEMANTIC_CHUNK_RANGE_CONFLICT",
                    "语义子块最小长度不能大于最大长度。", snapshot,
                    List.of("chunk.semanticMinChars", "chunk.semanticMaxChars"),
                    "语义子块最小长度必须小于或等于最大长度。",
                    List.of("降低最小长度或提高最大长度。"));
        }
        if (chunk.getParentBlockOverlapChars() >= chunk.getParentBlockMaxChars()) {
            throw relationFailure("PARENT_BLOCK_OVERLAP_CONFLICT",
                    "父块重叠长度必须小于父块最大长度。", snapshot,
                    List.of("chunk.parentBlockOverlapChars", "chunk.parentBlockMaxChars"),
                    "父块重叠长度必须严格小于父块最大长度。",
                    List.of("降低父块重叠长度或提高父块最大长度。"));
        }
        if (chunk.getParentSemanticMinChars() > chunk.getParentSemanticMaxChars()) {
            throw relationFailure("PARENT_SEMANTIC_RANGE_CONFLICT",
                    "父块语义最小长度不能大于最大长度。", snapshot,
                    List.of("chunk.parentSemanticMinChars", "chunk.parentSemanticMaxChars"),
                    "父块语义最小长度必须小于或等于最大长度。",
                    List.of("降低父块语义最小长度或提高最大长度。"));
        }
    }

    private void requireComponent(Object component, String key) {
        if (component == null) {
            throw new SystemConfigValidationException("SYSTEM_CONFIG_COMPONENT_MISSING",
                    "系统配置快照缺少配置对象: " + key, List.of(key), Map.of(),
                    "完整配置快照必须包含 " + key + " 配置对象。",
                    List.of("检查系统配置初始化和迁移是否完整，补齐真实缺失项后再重试；不要随意填充默认值。"));
        }
    }

    private Object parseValue(ConfigDefinition definition, JsonNode value) {
        if (value == null || value.isNull()) {
            throw fieldFailure("SYSTEM_CONFIG_VALUE_MISSING", definition,
                    definition.label() + "不能为空。", value,
                    definition.label() + "必须提供非空值。",
                    List.of("填写该字段允许类型的值后重新提交。"));
        }
        if ("BOOLEAN".equals(definition.valueType())) {
            if (!value.isBoolean()) {
                throw fieldFailure("SYSTEM_CONFIG_VALUE_TYPE_MISMATCH", definition,
                        definition.label() + "必须是布尔值。", value,
                        definition.label() + "只允许 true 或 false 布尔值。",
                        List.of("使用页面开关选择启用或停用，不要提交字符串形式的布尔值。"));
            }
            return value.booleanValue();
        }
        if ("STRING".equals(definition.valueType())) {
            if (!value.isTextual()) {
                throw fieldFailure("SYSTEM_CONFIG_VALUE_TYPE_MISMATCH", definition,
                        definition.label() + "必须是文本。", value,
                        definition.label() + "只允许文本值。",
                        List.of("改为文本后重新提交。"));
            }
            String text = value.textValue();
            if (text == null || text.isBlank()) {
                throw fieldFailure("SYSTEM_CONFIG_VALUE_MISSING", definition,
                        definition.label() + "不能为空。", value,
                        definition.label() + "必须提供非空文本。",
                        List.of("填写该字段的真实值后重新提交。"));
            }
            if (definition.maxLength() != null && text.length() > definition.maxLength()) {
                throw fieldFailure("SYSTEM_CONFIG_TEXT_TOO_LONG", definition,
                        definition.label() + "不能超过 " + definition.maxLength() + " 个字符。", value,
                        definition.label() + "最多允许 " + definition.maxLength() + " 个字符。",
                        List.of("缩短字段内容后重新提交。"));
            }
            return text;
        }
        if (!value.isNumber()) {
            throw fieldFailure("SYSTEM_CONFIG_VALUE_TYPE_MISMATCH", definition,
                    definition.label() + "必须是数字。", value,
                    definition.label() + "只允许数字值。",
                    List.of("使用数字输入框提供值后重新提交。"));
        }
        BigDecimal decimal = value.decimalValue();
        if ((definition.minValue() != null && decimal.compareTo(definition.minValue()) < 0)
                || (definition.maxValue() != null && decimal.compareTo(definition.maxValue()) > 0)) {
            String range = definition.minValue().stripTrailingZeros().toPlainString()
                    + " 到 " + definition.maxValue().stripTrailingZeros().toPlainString();
            throw fieldFailure("SYSTEM_CONFIG_VALUE_OUT_OF_RANGE", definition,
                    definition.label() + "超出允许范围。仅允许 " + range + "。", value,
                    definition.label() + "仅允许 " + range + (definition.unit() == null ? "。" : " " + definition.unit() + "。"),
                    List.of("将值调整到允许范围内后重新提交。"));
        }
        return switch (definition.valueType()) {
        case "INTEGER" -> {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw fieldFailure("SYSTEM_CONFIG_VALUE_TYPE_MISMATCH", definition,
                        definition.label() + "必须是整数。", value,
                        definition.label() + "只允许 32 位整数。",
                        List.of("去掉小数部分或改为允许范围内的整数。"));
            }
            yield value.intValue();
        }
        case "LONG" -> {
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                throw fieldFailure("SYSTEM_CONFIG_VALUE_TYPE_MISMATCH", definition,
                        definition.label() + "必须是整数。", value,
                        definition.label() + "只允许整数。",
                        List.of("去掉小数部分或改为允许范围内的整数。"));
            }
            yield value.longValue();
        }
        case "DECIMAL" -> value.doubleValue();
        default -> throw fieldFailure("UNSUPPORTED_SYSTEM_CONFIG_VALUE_TYPE", definition,
                "不支持的配置类型: " + definition.valueType(), value,
                "当前应用版本无法处理该字段类型。", List.of("刷新应用版本或联系维护人员核对参数目录。"));
        };
    }

    private SystemConfigValidationException fieldFailure(String ruleId, ConfigDefinition definition,
            String message, JsonNode submitted, String constraint, List<String> suggestions) {
        Object value = definition.sensitive() ? "已提供" : submittedValue(submitted);
        Map<String, Object> values = value == null ? Map.of() : Map.of(definition.key(), value);
        return new SystemConfigValidationException(ruleId, message, List.of(definition.key()), values,
                constraint, suggestions);
    }

    private Object submittedValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.numberValue();
        if (value.isTextual()) return value.textValue();
        return value.getNodeType().name();
    }

    private SystemConfigValidationException relationFailure(String ruleId, String message,
            SystemConfigSnapshot snapshot, List<String> keys, String constraint, List<String> suggestions) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (String key : keys) {
            ConfigDefinition definition = definitionsByKey.get(key);
            if (definition == null) continue;
            Object value = read(snapshot, definition);
            if (definition.sensitive()) {
                if (value != null && !String.valueOf(value).isBlank()) values.put(key, "已配置");
            }
            else if (value != null) {
                values.put(key, value);
            }
        }
        return new SystemConfigValidationException(ruleId, message, keys, values, constraint, suggestions);
    }

    private static ConfigDefinition integer(String key, String category, String label, String description, int min,
            int max, String unit, String mode, Function<SystemConfigSnapshot, Object> getter,
            BiConsumer<SystemConfigSnapshot, Object> setter) {
        return definition(key, category, label, description, "INTEGER", "NUMBER", min, max, 1, 1, unit, null, mode,
                getter, setter);
    }

    private static ConfigDefinition longInteger(String key, String category, String label, String description, long min,
            long max, String unit, String mode, Function<SystemConfigSnapshot, Object> getter,
            BiConsumer<SystemConfigSnapshot, Object> setter) {
        return definition(key, category, label, description, "LONG", "NUMBER", min, max, 1, 1, unit, null, mode,
                getter, setter);
    }

    private static ConfigDefinition duration(String key, String category, String label, String description, long min,
            long max, String mode, Function<SystemConfigSnapshot, Object> getter,
            BiConsumer<SystemConfigSnapshot, Object> setter) {
        return definition(key, category, label, description, "LONG", "DURATION", min, max, 100, 1, "毫秒", null, mode,
                getter, setter);
    }

    private static ConfigDefinition percentage(String key, String category, String label, String description,
            String mode, Function<SystemConfigSnapshot, Object> getter,
            BiConsumer<SystemConfigSnapshot, Object> setter) {
        return definition(key, category, label, description, "DECIMAL", "PERCENTAGE", 0, 1, 0.01, 100, "%", null, mode,
                getter, setter);
    }

    private static ConfigDefinition decimal(String key, String category, String label, String description, double min,
            double max, double step, String unit, String mode, Function<SystemConfigSnapshot, Object> getter,
            BiConsumer<SystemConfigSnapshot, Object> setter) {
        return definition(key, category, label, description, "DECIMAL", "NUMBER", min, max, step, 1, unit, null, mode,
                getter, setter);
    }

    private static ConfigDefinition bool(String key, String category, String label, String description, String mode,
            Function<SystemConfigSnapshot, Object> getter, BiConsumer<SystemConfigSnapshot, Object> setter) {
        return definition(key, category, label, description, "BOOLEAN", "CHECKBOX", null, null, null, 1, "", null, mode,
                getter, setter);
    }

    private static ConfigDefinition text(String key, String category, String label, String description, int maxLength,
            String mode, Function<SystemConfigSnapshot, Object> getter,
            BiConsumer<SystemConfigSnapshot, Object> setter) {
        return definition(key, category, label, description, "STRING", "TEXTAREA", null, null, null, 1, "", maxLength,
                mode, getter, setter);
    }

    private static ConfigDefinition secretText(String key, String category, String label, String description, int maxLength,
            String mode, Function<SystemConfigSnapshot, Object> getter,
            BiConsumer<SystemConfigSnapshot, Object> setter) {
        return new ConfigDefinition(key, category, label, description, null, relatedConfigKeys(key), "STRING", "PASSWORD",
                null, null, null, 1, "", maxLength, mode, true, getter, setter);
    }

    private static ConfigDefinition definition(String key, String category, String label, String description,
            String valueType, String controlType, Number min, Number max, Number step, int displayScale, String unit,
            Integer maxLength, String mode, Function<SystemConfigSnapshot, Object> getter,
            BiConsumer<SystemConfigSnapshot, Object> setter) {
        return new ConfigDefinition(key, category, label, description, null, relatedConfigKeys(key), valueType, controlType,
                decimal(min), decimal(max), decimal(step), displayScale, unit, maxLength, mode, false, getter, setter);
    }

    private static ConfigDefinition withRelationMetadata(ConfigDefinition definition,
            Map<String, ConfigDefinition> definitionsByKey) {
        return new ConfigDefinition(definition.key(), definition.categoryKey(), definition.label(), definition.description(),
            relationHint(definition.key(), definitionsByKey), definition.relatedConfigKeys(), definition.valueType(),
            definition.controlType(), definition.minValue(), definition.maxValue(), definition.step(),
            definition.displayScale(), definition.unit(), definition.maxLength(), definition.effectiveMode(),
            definition.sensitive(), definition.getter(), definition.setter());
    }

    private static List<String> relatedConfigKeys(String key) {
        return switch (key) {
        case "mvcAsync.corePoolSize", "mvcAsync.maxPoolSize" ->
            relatedKeysExcept(key, "mvcAsync.corePoolSize", "mvcAsync.maxPoolSize");
        case "graphRag.extraction.inputTokenBudget", "graphRag.model.modelContextTokens",
             "graphRag.model.outputReserve", "graphRag.model.promptReserve" ->
            relatedKeysExcept(key, "graphRag.extraction.inputTokenBudget", "graphRag.model.modelContextTokens",
                "graphRag.model.outputReserve", "graphRag.model.promptReserve");
        case "ragRuntime.candidateTopK", "ragRuntime.rerankCandidateTopK", "ragRuntime.finalTopK" ->
            relatedKeysExcept(key, "ragRuntime.candidateTopK", "ragRuntime.rerankCandidateTopK", "ragRuntime.finalTopK");
        case "ragRuntime.raptorSourceChunkTopK", "ragRuntime.raptorTopK" ->
            relatedKeysExcept(key, "ragRuntime.raptorSourceChunkTopK", "ragRuntime.raptorTopK");
        case "ragRuntime.channelTimeoutMs", "ragRuntime.subQuestionTimeoutMs" ->
            relatedKeysExcept(key, "ragRuntime.channelTimeoutMs", "ragRuntime.subQuestionTimeoutMs");
        case "chat.maxModelCallsPerThread", "chat.maxModelCallsPerRun" ->
            relatedKeysExcept(key, "chat.maxModelCallsPerThread", "chat.maxModelCallsPerRun");
        case "chat.maxToolCallsPerThread", "chat.maxToolCallsPerRun" ->
            relatedKeysExcept(key, "chat.maxToolCallsPerThread", "chat.maxToolCallsPerRun");
        case "rag.perSubQuestionEvidenceMaxChars", "rag.totalEvidenceMaxChars" ->
            relatedKeysExcept(key, "rag.perSubQuestionEvidenceMaxChars", "rag.totalEvidenceMaxChars");
        case "ragTools.graphExtractReadTimeoutMs", "graphRag.execution.toolBudgetMillis" ->
            relatedKeysExcept(key, "ragTools.graphExtractReadTimeoutMs", "graphRag.execution.toolBudgetMillis",
                "graphRag.execution.reserveMillis");
        case "ragTools.connectTimeoutMs" -> List.of("graphRag.execution.reserveMillis");
        case "graphRag.execution.reserveMillis" -> List.of(
            "ragTools.connectTimeoutMs", "ragTools.graphExtractReadTimeoutMs",
            "graphRag.execution.toolBudgetMillis", "graphRag.execution.documentBudgetMillis");
        case "graphRag.execution.workerThreads", "graphRag.execution.documentConcurrency" ->
            relatedKeysExcept(key, "graphRag.execution.documentConcurrency", "graphRag.execution.workerThreads");
        case "graphRag.execution.documentBudgetMillis" -> List.of("graphRag.execution.reserveMillis");
        case "chunkEnrichment.execution.workerThreads", "chunkEnrichment.execution.documentConcurrency" ->
            relatedKeysExcept(key, "chunkEnrichment.execution.documentConcurrency", "chunkEnrichment.execution.workerThreads");
        case "chunkEnrichment.execution.batchTimeoutMillis", "chunkEnrichment.execution.documentBudgetMillis" ->
            relatedKeysExcept(key, "chunkEnrichment.execution.documentBudgetMillis", "chunkEnrichment.execution.batchTimeoutMillis");
        case "chunk.recursiveMaxChars", "chunk.recursiveOverlapChars" ->
            relatedKeysExcept(key, "chunk.recursiveOverlapChars", "chunk.recursiveMaxChars");
        case "chunk.semanticMaxChars", "chunk.semanticMinChars" ->
            relatedKeysExcept(key, "chunk.semanticMinChars", "chunk.semanticMaxChars");
        case "chunk.parentBlockMaxChars", "chunk.parentBlockOverlapChars" ->
            relatedKeysExcept(key, "chunk.parentBlockOverlapChars", "chunk.parentBlockMaxChars");
        case "chunk.parentSemanticMaxChars", "chunk.parentSemanticMinChars" ->
            relatedKeysExcept(key, "chunk.parentSemanticMinChars", "chunk.parentSemanticMaxChars");
        default -> List.of();
        };
    }

    private static List<String> relatedKeysExcept(String currentKey, String... relationKeys) {
        return List.of(relationKeys).stream()
            .filter(key -> !key.equals(currentKey))
            .toList();
    }

    private static String relationHint(String key, Map<String, ConfigDefinition> definitionsByKey) {
        return switch (key) {
        case "mvcAsync.corePoolSize", "mvcAsync.maxPoolSize" ->
            "必须满足：%s ≤ %s。".formatted(label(definitionsByKey, "mvcAsync.corePoolSize"),
                label(definitionsByKey, "mvcAsync.maxPoolSize"));
        case "graphRag.extraction.inputTokenBudget", "graphRag.model.modelContextTokens",
             "graphRag.model.outputReserve", "graphRag.model.promptReserve" ->
            "必须满足：%s ≤ %s − %s − %s。".formatted(
                label(definitionsByKey, "graphRag.extraction.inputTokenBudget"),
                label(definitionsByKey, "graphRag.model.modelContextTokens"),
                label(definitionsByKey, "graphRag.model.outputReserve"),
                label(definitionsByKey, "graphRag.model.promptReserve"));
        case "ragRuntime.candidateTopK", "ragRuntime.rerankCandidateTopK", "ragRuntime.finalTopK" ->
            "必须满足：%s ≥ %s ≥ %s。".formatted(
                label(definitionsByKey, "ragRuntime.candidateTopK"),
                label(definitionsByKey, "ragRuntime.rerankCandidateTopK"),
                label(definitionsByKey, "ragRuntime.finalTopK"));
        case "ragRuntime.raptorSourceChunkTopK", "ragRuntime.raptorTopK" ->
            "必须满足：%s ≤ %s。".formatted(
                label(definitionsByKey, "ragRuntime.raptorSourceChunkTopK"),
                label(definitionsByKey, "ragRuntime.raptorTopK"));
        case "ragRuntime.channelTimeoutMs", "ragRuntime.subQuestionTimeoutMs" ->
            "必须满足：%s ≤ %s。".formatted(
                label(definitionsByKey, "ragRuntime.channelTimeoutMs"),
                label(definitionsByKey, "ragRuntime.subQuestionTimeoutMs"));
        case "chat.maxModelCallsPerThread", "chat.maxModelCallsPerRun" ->
            "必须满足：%s ≥ %s。".formatted(
                label(definitionsByKey, "chat.maxModelCallsPerThread"),
                label(definitionsByKey, "chat.maxModelCallsPerRun"));
        case "chat.maxToolCallsPerThread", "chat.maxToolCallsPerRun" ->
            "必须满足：%s ≥ %s。".formatted(
                label(definitionsByKey, "chat.maxToolCallsPerThread"),
                label(definitionsByKey, "chat.maxToolCallsPerRun"));
        case "rag.perSubQuestionEvidenceMaxChars", "rag.totalEvidenceMaxChars" ->
            "必须满足：%s ≤ %s。".formatted(
                label(definitionsByKey, "rag.perSubQuestionEvidenceMaxChars"),
                label(definitionsByKey, "rag.totalEvidenceMaxChars"));
        case "ragTools.graphExtractReadTimeoutMs", "graphRag.execution.toolBudgetMillis" ->
            "必须满足：%s ≥ %s + %s。".formatted(
                label(definitionsByKey, "ragTools.graphExtractReadTimeoutMs"),
                label(definitionsByKey, "graphRag.execution.toolBudgetMillis"),
                label(definitionsByKey, "graphRag.execution.reserveMillis"));
        case "ragTools.connectTimeoutMs" ->
            "必须满足：%s ≤ %s。".formatted(
                label(definitionsByKey, "ragTools.connectTimeoutMs"),
                label(definitionsByKey, "graphRag.execution.reserveMillis"));
        case "graphRag.execution.reserveMillis" ->
            "必须同时满足：%s ≤ %s；%s ≥ %s + %s；%s > %s。".formatted(
                label(definitionsByKey, "ragTools.connectTimeoutMs"),
                label(definitionsByKey, "graphRag.execution.reserveMillis"),
                label(definitionsByKey, "ragTools.graphExtractReadTimeoutMs"),
                label(definitionsByKey, "graphRag.execution.toolBudgetMillis"),
                label(definitionsByKey, "graphRag.execution.reserveMillis"),
                label(definitionsByKey, "graphRag.execution.documentBudgetMillis"),
                label(definitionsByKey, "graphRag.execution.reserveMillis"));
        case "graphRag.execution.workerThreads", "graphRag.execution.documentConcurrency" ->
            "必须满足：%s ≤ %s。".formatted(
                label(definitionsByKey, "graphRag.execution.documentConcurrency"),
                label(definitionsByKey, "graphRag.execution.workerThreads"));
        case "graphRag.execution.documentBudgetMillis" ->
            "必须满足：%s > %s。".formatted(
                label(definitionsByKey, "graphRag.execution.documentBudgetMillis"),
                label(definitionsByKey, "graphRag.execution.reserveMillis"));
        case "chunkEnrichment.execution.workerThreads", "chunkEnrichment.execution.documentConcurrency" ->
            "必须满足：%s ≤ %s。".formatted(
                label(definitionsByKey, "chunkEnrichment.execution.documentConcurrency"),
                label(definitionsByKey, "chunkEnrichment.execution.workerThreads"));
        case "chunkEnrichment.execution.batchTimeoutMillis", "chunkEnrichment.execution.documentBudgetMillis" ->
            "必须满足：%s ≥ %s。".formatted(
                label(definitionsByKey, "chunkEnrichment.execution.documentBudgetMillis"),
                label(definitionsByKey, "chunkEnrichment.execution.batchTimeoutMillis"));
        case "chunk.recursiveMaxChars", "chunk.recursiveOverlapChars" ->
            "必须满足：%s < %s。".formatted(
                label(definitionsByKey, "chunk.recursiveOverlapChars"),
                label(definitionsByKey, "chunk.recursiveMaxChars"));
        case "chunk.semanticMaxChars", "chunk.semanticMinChars" ->
            "必须满足：%s ≤ %s。".formatted(
                label(definitionsByKey, "chunk.semanticMinChars"),
                label(definitionsByKey, "chunk.semanticMaxChars"));
        case "chunk.parentBlockMaxChars", "chunk.parentBlockOverlapChars" ->
            "必须满足：%s < %s。".formatted(
                label(definitionsByKey, "chunk.parentBlockOverlapChars"),
                label(definitionsByKey, "chunk.parentBlockMaxChars"));
        case "chunk.parentSemanticMaxChars", "chunk.parentSemanticMinChars" ->
            "必须满足：%s ≤ %s。".formatted(
                label(definitionsByKey, "chunk.parentSemanticMinChars"),
                label(definitionsByKey, "chunk.parentSemanticMaxChars"));
        default -> null;
        };
    }

    private static String label(Map<String, ConfigDefinition> definitionsByKey, String key) {
        ConfigDefinition definition = definitionsByKey.get(key);
        if (definition == null) {
            throw new IllegalStateException("系统配置关联键不存在: " + key);
        }
        return definition.label();
    }

    private static BigDecimal decimal(Number value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private SuperAgentFrameException parameterError(String message) {
        return new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), message);
    }

    public record ConfigDefinition(String key, String categoryKey, String label, String description, String relationHint,
            List<String> relatedConfigKeys, String valueType, String controlType,
            BigDecimal minValue, BigDecimal maxValue, BigDecimal step, int displayScale,
            String unit, Integer maxLength, String effectiveMode, boolean sensitive,
            Function<SystemConfigSnapshot, Object> getter,
            BiConsumer<SystemConfigSnapshot, Object> setter) {
        public ConfigDefinition {
            relatedConfigKeys = relatedConfigKeys == null ? List.of() : List.copyOf(relatedConfigKeys);
        }
    }
}
