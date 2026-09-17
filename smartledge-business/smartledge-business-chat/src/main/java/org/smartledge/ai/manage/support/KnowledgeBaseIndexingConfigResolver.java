package org.smartledge.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.knowledge.indexing.port.IndexingConfigurationPort;
import org.smartledge.ai.manage.config.DocumentManageProperties;
import org.smartledge.ai.manage.data.SuperAgentDocument;
import org.smartledge.ai.manage.data.SuperAgentKnowledgeBase;
import org.smartledge.ai.manage.mapper.SuperAgentDocumentMapper;
import org.smartledge.ai.manage.model.KnowledgeBaseIndexingOptions;
import org.smartledge.ai.manage.model.SystemConfigSnapshot;
import org.smartledge.ai.manage.service.KnowledgeBaseManageService;
import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KnowledgeBaseIndexingConfigResolver {

    private final IndexingConfigurationPort indexingConfigurationPort;
    private final SuperAgentDocumentMapper documentMapper;
    private final KnowledgeBaseManageService knowledgeBaseManageService;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private SystemConfigProvider systemConfigProvider;

    public KnowledgeBaseIndexingConfigResolver(DocumentManageProperties properties, SystemConfigProvider provider) {
        this(properties, null, null);
        this.systemConfigProvider = provider;
    }

    @Autowired
    public KnowledgeBaseIndexingConfigResolver(DocumentManageProperties properties,
                                               SuperAgentDocumentMapper documentMapper,
                                               KnowledgeBaseManageService knowledgeBaseManageService) {
        this.indexingConfigurationPort = new DocumentManageIndexingConfigurationAdapter(properties);
        this.documentMapper = documentMapper;
        this.knowledgeBaseManageService = knowledgeBaseManageService;
        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public KnowledgeBaseIndexingOptions resolve(SuperAgentDocument document) {
        if (document == null || document.getKnowledgeBaseId() == null) {
            return defaults();
        }
        return resolveByKnowledgeBaseId(document.getKnowledgeBaseId());
    }

    public KnowledgeBaseIndexingOptions resolveByDocumentId(Long documentId) {
        if (documentId == null || documentId <= 0 || documentMapper == null) {
            throw new IllegalArgumentException("documentId 无效，无法解析知识库生效配置");
        }
        SuperAgentDocument document = documentMapper.selectById(documentId);
        return resolve(document);
    }

    public KnowledgeBaseIndexingOptions resolveByKnowledgeBaseId(Long knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId <= 0 || knowledgeBaseManageService == null) {
            throw new IllegalArgumentException("knowledgeBaseId 无效，无法解析知识库生效配置");
        }
        return resolve(knowledgeBaseManageService.requireEnabled(knowledgeBaseId));
    }

    public KnowledgeBaseIndexingOptions resolve(SuperAgentKnowledgeBase knowledgeBase) {
        KnowledgeBaseIndexingOptions options = defaults();
        if (knowledgeBase == null) {
            return options;
        }
        RetrievalConfig retrievalConfig = parseJson(knowledgeBase.getRetrievalConfigJson(), RetrievalConfig.class, knowledgeBase);
        GraphRagConfig graphRagConfig = parseJson(knowledgeBase.getGraphRagConfigJson(), GraphRagConfig.class, knowledgeBase);
        RaptorConfig raptorConfig = parseJson(knowledgeBase.getRaptorConfigJson(), RaptorConfig.class, knowledgeBase);
        applyIndexing(options.getChunk(), retrievalConfig == null ? null : retrievalConfig.getIndexing());
        applyGraphRagBuild(options.getGraphRag(), graphRagConfig == null ? null : graphRagConfig.getBuild());
        applyRaptorBuild(options.getRaptor(), raptorConfig == null ? null : raptorConfig.getBuild());
        validate(options);
        return options;
    }

    private KnowledgeBaseIndexingOptions defaults() {
        if (systemConfigProvider == null) {
            throw new IllegalStateException("系统配置提供方未装配，拒绝使用知识库默认配置");
        }
        SystemConfigSnapshot snapshot = systemConfigProvider.currentSnapshot();
        if (snapshot == null || snapshot.getRag() == null) {
            throw new IllegalStateException("系统配置快照缺失，拒绝使用知识库默认配置");
        }
        if (snapshot.getChunk() == null) {
            throw new IllegalStateException("系统配置缺少知识库切块基础配置");
        }
        DocumentManageProperties.Chunk chunk = snapshot.getChunk();
        KnowledgeBaseIndexingOptions options = KnowledgeBaseIndexingOptions.fromDefaults(
            () -> new IndexingConfigurationPort.ChunkDefaults(
                chunk.getRecursiveMaxChars(),
                chunk.getRecursiveOverlapChars(),
                chunk.getSemanticMaxChars(),
                chunk.getSemanticMinChars(),
                chunk.getSemanticSimilarityThreshold(),
                chunk.getParentBlockMaxChars(),
                chunk.getParentBlockOverlapChars(),
                chunk.getParentSemanticMaxChars(),
                chunk.getParentSemanticMinChars()),
            snapshot.getRag().getRaptorMaxClusterSize(),
            snapshot.getRag().getRaptorMaxLevels(),
            snapshot.getRag().isRaptorLlmSummaryEnabled(),
            snapshot.getRag().getRaptorSummaryQualityFloor()
        );
        validate(options);
        return options;
    }

    private void applyIndexing(KnowledgeBaseIndexingOptions.ChunkOptions target, IndexingConfig source) {
        if (target == null || source == null) {
            return;
        }
        copyIfPresent(source.getChildRecursiveMaxChars(), target::setChildRecursiveMaxChars);
        copyIfPresent(source.getChildRecursiveOverlapChars(), target::setChildRecursiveOverlapChars);
        copyIfPresent(source.getChildSemanticMaxChars(), target::setChildSemanticMaxChars);
        copyIfPresent(source.getChildSemanticMinChars(), target::setChildSemanticMinChars);
        copyIfPresent(source.getChildSemanticSimilarityThreshold(), target::setChildSemanticSimilarityThreshold);
        copyIfPresent(source.getParentBlockMaxChars(), target::setParentBlockMaxChars);
        copyIfPresent(source.getParentBlockOverlapChars(), target::setParentBlockOverlapChars);
        copyIfPresent(source.getParentSemanticMaxChars(), target::setParentSemanticMaxChars);
        copyIfPresent(source.getParentSemanticMinChars(), target::setParentSemanticMinChars);
    }

    private void applyGraphRagBuild(KnowledgeBaseIndexingOptions.GraphRagBuildOptions target, GraphRagBuildConfig source) {
        if (target == null || source == null) {
            return;
        }
        copyIfPresent(source.getGraphRagBuildEnabled(), target::setGraphRagBuildEnabled);
    }

    private void applyRaptorBuild(KnowledgeBaseIndexingOptions.RaptorBuildOptions target, RaptorBuildConfig source) {
        if (target == null || source == null) {
            return;
        }
        copyIfPresent(source.getRaptorBuildEnabled(), target::setRaptorBuildEnabled);
        copyIfPresent(source.getRaptorMaxClusterSize(), target::setRaptorMaxClusterSize);
        copyIfPresent(source.getRaptorMaxLevels(), target::setRaptorMaxLevels);
        copyIfPresent(source.getRaptorLlmSummaryEnabled(), target::setRaptorLlmSummaryEnabled);
        copyIfPresent(source.getRaptorSummaryQualityFloor(), target::setRaptorSummaryQualityFloor);
    }

    private void validate(KnowledgeBaseIndexingOptions options) {
        KnowledgeBaseIndexingOptions.ChunkOptions chunk = options.getChunk();
        requireInt("childRecursiveMaxChars", chunk.getChildRecursiveMaxChars(), 100, 8000);
        requireInt("childRecursiveOverlapChars", chunk.getChildRecursiveOverlapChars(), 0, chunk.getChildRecursiveMaxChars() - 1);
        requireInt("childSemanticMaxChars", chunk.getChildSemanticMaxChars(), 100, 8000);
        requireInt("childSemanticMinChars", chunk.getChildSemanticMinChars(), 80, chunk.getChildSemanticMaxChars());
        requireDouble("childSemanticSimilarityThreshold", chunk.getChildSemanticSimilarityThreshold(), 0D, 1D);
        requireInt("parentBlockMaxChars", chunk.getParentBlockMaxChars(), 300, 20000);
        requireInt("parentBlockOverlapChars", chunk.getParentBlockOverlapChars(), 0, chunk.getParentBlockMaxChars() - 1);
        requireInt("parentSemanticMaxChars", chunk.getParentSemanticMaxChars(), 300, 20000);
        requireInt("parentSemanticMinChars", chunk.getParentSemanticMinChars(), 120, chunk.getParentSemanticMaxChars());

        KnowledgeBaseIndexingOptions.GraphRagBuildOptions graphRag = options.getGraphRag();
        requireBoolean("graphRagBuildEnabled", graphRag.getGraphRagBuildEnabled());

        KnowledgeBaseIndexingOptions.RaptorBuildOptions raptor = options.getRaptor();
        requireBoolean("raptorBuildEnabled", raptor.getRaptorBuildEnabled());
        requireInt("raptorMaxClusterSize", raptor.getRaptorMaxClusterSize(), 2, 50);
        requireInt("raptorMaxLevels", raptor.getRaptorMaxLevels(), 1, 8);
        if (raptor.getRaptorLlmSummaryEnabled() == null) {
            throw new IllegalArgumentException("知识库配置缺少 raptorLlmSummaryEnabled");
        }
        requireDouble("raptorSummaryQualityFloor", raptor.getRaptorSummaryQualityFloor(), 0D, 1D);
    }

    private void requireInt(String key, Integer value, int min, int max) {
        if (value == null || value < min || value > max) {
            throw new IllegalArgumentException("知识库配置 " + key + " 超出允许范围");
        }
    }

    private void requireDouble(String key, Double value, double min, double max) {
        if (value == null || !Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException("知识库配置 " + key + " 超出允许范围");
        }
    }

    private void requireBoolean(String key, Boolean value) {
        if (value == null) {
            throw new IllegalArgumentException("知识库配置缺少 " + key);
        }
    }

    private <T> T parseJson(String rawJson, Class<T> targetClass, SuperAgentKnowledgeBase knowledgeBase) {
        if (StrUtil.isBlank(rawJson)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode tree = objectMapper.readTree(rawJson);
            if (tree == null || tree.isNull() || containsExplicitNull(tree)) {
                throw new IllegalArgumentException("知识库配置字段不能显式为空");
            }
            return objectMapper.treeToValue(tree, targetClass);
        }
        catch (JsonProcessingException exception) {
            log.warn("知识库解析/索引构建配置 JSON 解析失败，拒绝继续: knowledgeBaseId={}, knowledgeBaseName={}, targetClass={}",
                knowledgeBase == null ? null : knowledgeBase.getId(),
                knowledgeBase == null ? "" : knowledgeBase.getBaseName(),
                targetClass == null ? "" : targetClass.getSimpleName(),
                exception);
            throw new IllegalArgumentException("知识库配置 JSON 非法", exception);
        }
        catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("显式为空")) {
                throw exception;
            }
            log.warn("知识库解析/索引构建配置 JSON 解析失败，拒绝继续: knowledgeBaseId={}, knowledgeBaseName={}, targetClass={}",
                knowledgeBase == null ? null : knowledgeBase.getId(),
                knowledgeBase == null ? "" : knowledgeBase.getBaseName(),
                targetClass == null ? "" : targetClass.getSimpleName(),
                exception);
            throw new IllegalArgumentException("知识库配置 JSON 非法", exception);
        }
    }

    private boolean containsExplicitNull(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isNull()) {
            return true;
        }
        if (node.isContainerNode()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                if (containsExplicitNull(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private <T> void copyIfPresent(T value, java.util.function.Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    @Data
    private static class RetrievalConfig {

        private IndexingConfig indexing;
    }

    @Data
    private static class IndexingConfig {

        private Integer childRecursiveMaxChars;

        private Integer childRecursiveOverlapChars;

        private Integer childSemanticMaxChars;

        private Integer childSemanticMinChars;

        private Double childSemanticSimilarityThreshold;

        private Integer parentBlockMaxChars;

        private Integer parentBlockOverlapChars;

        private Integer parentSemanticMaxChars;

        private Integer parentSemanticMinChars;
    }

    @Data
    private static class GraphRagConfig {

        private GraphRagBuildConfig build;
    }

    @Data
    private static class GraphRagBuildConfig {

        private Boolean graphRagBuildEnabled;
    }

    @Data
    private static class RaptorConfig {

        private RaptorBuildConfig build;
    }

    @Data
    private static class RaptorBuildConfig {

        private Boolean raptorBuildEnabled;

        private Integer raptorMaxClusterSize;

        private Integer raptorMaxLevels;

        private Boolean raptorLlmSummaryEnabled;

        private Double raptorSummaryQualityFloor;
    }
}
