package org.smartledge.ai.rag.runtime;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.smartledge.ai.rag.runtime.model.RagRuntimeOptions;
import org.smartledge.ai.rag.runtime.model.KnowledgeBaseRuntimeConfigSource;
import org.smartledge.ai.rag.runtime.port.GlobalRagRuntimeConfigProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

@Slf4j
@Service
public class KnowledgeBaseRuntimeConfigResolver {

    private final GlobalRagRuntimeConfigProvider globalConfigProvider;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseRuntimeConfigResolver(GlobalRagRuntimeConfigProvider globalConfigProvider) {
        this.globalConfigProvider = globalConfigProvider;
        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public RagRuntimeOptions resolve(List<KnowledgeBaseRuntimeConfigSource> knowledgeBases) {
        RagRuntimeOptions options = globalConfigProvider.currentOptions();
        List<RuntimeConfig> configs = knowledgeBases == null
            ? List.of()
            : knowledgeBases.stream()
                .filter(Objects::nonNull)
                .map(this::parseConfig)
                .toList();
        if (configs.isEmpty()) {
            return options;
        }
        if (configs.size() == 1) {
            applySingle(options, configs.get(0));
            return options;
        }
        applyMerged(options, configs);
        return options;
    }

    private void applySingle(RagRuntimeOptions options, RuntimeConfig config) {
        applyIfPresent(config.getVectorTopK(), options::setVectorTopK);
        applyIfPresent(config.getKeywordTopK(), options::setKeywordTopK);
        applyIfPresent(config.getGraphRagTopK(), options::setGraphRagTopK);
        applyIfPresent(config.getGraphRagMaxHops(), options::setGraphRagMaxHops);
        applyIfPresent(config.getRaptorTopK(), options::setRaptorTopK);
        applyIfPresent(config.getRaptorSourceChunkTopK(), options::setRaptorSourceChunkTopK);
        applyIfPresent(config.getCandidateTopK(), options::setCandidateTopK);
        applyIfPresent(config.getRerankCandidateTopK(), options::setRerankCandidateTopK);
        applyIfPresent(config.getFinalTopK(), options::setFinalTopK);
        applyIfPresent(config.getRerankEnabled(), options::setRerankEnabled);
        applyIfPresent(config.getChannelTimeoutMs(), options::setChannelTimeoutMs);
        applyIfPresent(config.getSubQuestionTimeoutMs(), options::setSubQuestionTimeoutMs);
        applyIfPresent(config.getMinVectorSimilarity(), options::setMinVectorSimilarity);
        applyIfPresent(config.getKeywordRelativeScoreFloor(), options::setKeywordRelativeScoreFloor);
        applyIfPresent(config.getKeywordChannelEnabled(), options::setKeywordChannelEnabled);
        applyIfPresent(config.getTableChannelEnabled(), options::setTableChannelEnabled);
        applyIfPresent(config.getGraphRagChannelEnabled(), options::setGraphRagChannelEnabled);
        applyIfPresent(config.getRaptorChannelEnabled(), options::setRaptorChannelEnabled);
        applySingleHybrid(options.getHybrid(), config.getHybrid());
    }

    private void applySingleHybrid(RagRuntimeOptions.HybridOptions options, HybridConfig config) {
        if (config == null || options == null) {
            return;
        }
        applyIfPresent(config.getVectorWeight(), options::setVectorWeight);
        applyIfPresent(config.getKeywordWeight(), options::setKeywordWeight);
        applyIfPresent(config.getTableWeight(), options::setTableWeight);
        applyIfPresent(config.getGraphRagWeight(), options::setGraphRagWeight);
        applyIfPresent(config.getRaptorWeight(), options::setRaptorWeight);
        applyIfPresent(config.getRankWeight(), options::setRankWeight);
        applyIfPresent(config.getOriginalScoreWeight(), options::setOriginalScoreWeight);
        applyIfPresent(config.getMetadataBoostWeight(), options::setMetadataBoostWeight);
        applyIfPresent(config.getMaxMetadataBoost(), options::setMaxMetadataBoost);
    }

    private void applyMerged(RagRuntimeOptions options, List<RuntimeConfig> configs) {
        List<String> conflicts = new ArrayList<>();
        mergeField(configs, RuntimeConfig::getVectorTopK, options::setVectorTopK, "vectorTopK", conflicts);
        mergeField(configs, RuntimeConfig::getKeywordTopK, options::setKeywordTopK, "keywordTopK", conflicts);
        mergeField(configs, RuntimeConfig::getGraphRagTopK, options::setGraphRagTopK, "graphRagTopK", conflicts);
        mergeField(configs, RuntimeConfig::getGraphRagMaxHops, options::setGraphRagMaxHops, "graphRagMaxHops", conflicts);
        mergeField(configs, RuntimeConfig::getRaptorTopK, options::setRaptorTopK, "raptorTopK", conflicts);
        mergeField(configs, RuntimeConfig::getRaptorSourceChunkTopK, options::setRaptorSourceChunkTopK, "raptorSourceChunkTopK", conflicts);
        mergeField(configs, RuntimeConfig::getCandidateTopK, options::setCandidateTopK, "candidateTopK", conflicts);
        mergeField(configs, RuntimeConfig::getRerankCandidateTopK, options::setRerankCandidateTopK, "rerankCandidateTopK", conflicts);
        mergeField(configs, RuntimeConfig::getFinalTopK, options::setFinalTopK, "finalTopK", conflicts);
        mergeField(configs, RuntimeConfig::getRerankEnabled, options::setRerankEnabled, "rerankEnabled", conflicts);
        mergeField(configs, RuntimeConfig::getChannelTimeoutMs, options::setChannelTimeoutMs, "channelTimeoutMs", conflicts);
        mergeField(configs, RuntimeConfig::getSubQuestionTimeoutMs, options::setSubQuestionTimeoutMs, "subQuestionTimeoutMs", conflicts);
        mergeField(configs, RuntimeConfig::getMinVectorSimilarity, options::setMinVectorSimilarity, "minVectorSimilarity", conflicts);
        mergeField(configs, RuntimeConfig::getMinEvidenceConfidence, options::setMinEvidenceConfidence, "minEvidenceConfidence", conflicts);
        mergeField(configs, RuntimeConfig::getKeywordRelativeScoreFloor, options::setKeywordRelativeScoreFloor, "keywordRelativeScoreFloor", conflicts);
        mergeField(configs, RuntimeConfig::getKeywordChannelEnabled, options::setKeywordChannelEnabled, "keywordChannelEnabled", conflicts);
        mergeField(configs, RuntimeConfig::getTableChannelEnabled, options::setTableChannelEnabled, "tableChannelEnabled", conflicts);
        mergeField(configs, RuntimeConfig::getGraphRagChannelEnabled, options::setGraphRagChannelEnabled, "graphRagChannelEnabled", conflicts);
        mergeField(configs, RuntimeConfig::getRaptorChannelEnabled, options::setRaptorChannelEnabled, "raptorChannelEnabled", conflicts);

        mergeHybridField(configs, HybridConfig::getVectorWeight, options.getHybrid()::setVectorWeight, "hybrid.vectorWeight", conflicts);
        mergeHybridField(configs, HybridConfig::getKeywordWeight, options.getHybrid()::setKeywordWeight, "hybrid.keywordWeight", conflicts);
        mergeHybridField(configs, HybridConfig::getTableWeight, options.getHybrid()::setTableWeight, "hybrid.tableWeight", conflicts);
        mergeHybridField(configs, HybridConfig::getGraphRagWeight, options.getHybrid()::setGraphRagWeight, "hybrid.graphRagWeight", conflicts);
        mergeHybridField(configs, HybridConfig::getRaptorWeight, options.getHybrid()::setRaptorWeight, "hybrid.raptorWeight", conflicts);
        mergeHybridField(configs, HybridConfig::getRankWeight, options.getHybrid()::setRankWeight, "hybrid.rankWeight", conflicts);
        mergeHybridField(configs, HybridConfig::getOriginalScoreWeight, options.getHybrid()::setOriginalScoreWeight, "hybrid.originalScoreWeight", conflicts);
        mergeHybridField(configs, HybridConfig::getMetadataBoostWeight, options.getHybrid()::setMetadataBoostWeight, "hybrid.metadataBoostWeight", conflicts);
        mergeHybridField(configs, HybridConfig::getMaxMetadataBoost, options.getHybrid()::setMaxMetadataBoost, "hybrid.maxMetadataBoost", conflicts);

        options.setKbConfigConflictFields(new ArrayList<>(new LinkedHashSet<>(conflicts)));
    }

    private <T> void mergeHybridField(List<RuntimeConfig> configs,
                                      Function<HybridConfig, T> getter,
                                      java.util.function.Consumer<T> setter,
                                      String field,
                                      List<String> conflicts) {
        mergeField(configs,
            config -> config.getHybrid() == null ? null : getter.apply(config.getHybrid()),
            setter,
            field,
            conflicts);
    }

    private <T> void mergeField(List<RuntimeConfig> configs,
                                Function<RuntimeConfig, T> getter,
                                java.util.function.Consumer<T> setter,
                                String field,
                                List<String> conflicts) {
        List<T> values = configs.stream().map(getter).toList();
        if (values.stream().allMatch(Objects::isNull)) {
            return;
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            conflicts.add(field);
            return;
        }
        T first = values.get(0);
        boolean same = values.stream().allMatch(value -> Objects.equals(first, value));
        if (same) {
            setter.accept(first);
        }
        else {
            conflicts.add(field);
        }
    }

    private RuntimeConfig parseConfig(KnowledgeBaseRuntimeConfigSource knowledgeBase) {
        RuntimeConfig merged = new RuntimeConfig();
        mergeInto(merged, parseJson(knowledgeBase.retrievalConfigJson(), knowledgeBase));
        mergeInto(merged, parseJson(knowledgeBase.graphRagConfigJson(), knowledgeBase));
        mergeInto(merged, parseJson(knowledgeBase.raptorConfigJson(), knowledgeBase));
        return merged;
    }

    private RuntimeConfig parseJson(String rawJson, KnowledgeBaseRuntimeConfigSource knowledgeBase) {
        if (StrUtil.isBlank(rawJson)) {
            return new RuntimeConfig();
        }
        try {
            return objectMapper.readValue(rawJson, RuntimeConfig.class);
        }
        catch (JsonProcessingException | RuntimeException exception) {
            log.warn("知识库 RAG 配置 JSON 解析失败，将忽略该段配置: knowledgeBaseId={}, knowledgeBaseName={}",
                knowledgeBase == null ? null : knowledgeBase.id(),
                knowledgeBase == null ? "" : knowledgeBase.name(),
                exception);
            return new RuntimeConfig();
        }
    }

    private void mergeInto(RuntimeConfig target, RuntimeConfig source) {
        if (source == null) {
            return;
        }
        copyIfPresent(source.getVectorTopK(), target::setVectorTopK);
        copyIfPresent(source.getKeywordTopK(), target::setKeywordTopK);
        copyIfPresent(source.getGraphRagTopK(), target::setGraphRagTopK);
        copyIfPresent(source.getGraphRagMaxHops(), target::setGraphRagMaxHops);
        copyIfPresent(source.getRaptorTopK(), target::setRaptorTopK);
        copyIfPresent(source.getRaptorSourceChunkTopK(), target::setRaptorSourceChunkTopK);
        copyIfPresent(source.getCandidateTopK(), target::setCandidateTopK);
        copyIfPresent(source.getRerankCandidateTopK(), target::setRerankCandidateTopK);
        copyIfPresent(source.getFinalTopK(), target::setFinalTopK);
        copyIfPresent(source.getRerankEnabled(), target::setRerankEnabled);
        copyIfPresent(source.getChannelTimeoutMs(), target::setChannelTimeoutMs);
        copyIfPresent(source.getSubQuestionTimeoutMs(), target::setSubQuestionTimeoutMs);
        copyIfPresent(source.getMinVectorSimilarity(), target::setMinVectorSimilarity);
        copyIfPresent(source.getKeywordRelativeScoreFloor(), target::setKeywordRelativeScoreFloor);
        copyIfPresent(source.getKeywordChannelEnabled(), target::setKeywordChannelEnabled);
        copyIfPresent(source.getTableChannelEnabled(), target::setTableChannelEnabled);
        copyIfPresent(source.getGraphRagChannelEnabled(), target::setGraphRagChannelEnabled);
        copyIfPresent(source.getRaptorChannelEnabled(), target::setRaptorChannelEnabled);
        if (source.getHybrid() != null) {
            if (target.getHybrid() == null) {
                target.setHybrid(new HybridConfig());
            }
            mergeHybridInto(target.getHybrid(), source.getHybrid());
        }
    }

    private void mergeHybridInto(HybridConfig target, HybridConfig source) {
        copyIfPresent(source.getVectorWeight(), target::setVectorWeight);
        copyIfPresent(source.getKeywordWeight(), target::setKeywordWeight);
        copyIfPresent(source.getTableWeight(), target::setTableWeight);
        copyIfPresent(source.getGraphRagWeight(), target::setGraphRagWeight);
        copyIfPresent(source.getRaptorWeight(), target::setRaptorWeight);
        copyIfPresent(source.getRankWeight(), target::setRankWeight);
        copyIfPresent(source.getOriginalScoreWeight(), target::setOriginalScoreWeight);
        copyIfPresent(source.getMetadataBoostWeight(), target::setMetadataBoostWeight);
        copyIfPresent(source.getMaxMetadataBoost(), target::setMaxMetadataBoost);
    }

    private <T> void applyIfPresent(T value, java.util.function.Consumer<T> setter) {
        copyIfPresent(value, setter);
    }

    private <T> void copyIfPresent(T value, java.util.function.Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    @Data
    public static class RuntimeConfig {
        private Integer vectorTopK;
        private Integer keywordTopK;
        private Integer graphRagTopK;
        private Integer graphRagMaxHops;
        private Integer raptorTopK;
        private Integer raptorSourceChunkTopK;
        private Integer candidateTopK;
        private Integer rerankCandidateTopK;
        private Integer finalTopK;
        private Boolean rerankEnabled;
        private Long channelTimeoutMs;
        private Long subQuestionTimeoutMs;
        private Double minVectorSimilarity;
        private Double minEvidenceConfidence;
        private Double keywordRelativeScoreFloor;
        private Boolean keywordChannelEnabled;
        private Boolean tableChannelEnabled;
        private Boolean graphRagChannelEnabled;
        private Boolean raptorChannelEnabled;
        private HybridConfig hybrid;
    }

    @Data
    public static class HybridConfig {
        private Double vectorWeight;
        private Double keywordWeight;
        private Double tableWeight;
        private Double graphRagWeight;
        private Double raptorWeight;
        private Double rankWeight;
        private Double originalScoreWeight;
        private Double metadataBoostWeight;
        private Double maxMetadataBoost;
    }
}
