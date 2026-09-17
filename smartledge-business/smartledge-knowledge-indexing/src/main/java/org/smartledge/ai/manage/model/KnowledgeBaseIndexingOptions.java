package org.smartledge.ai.manage.model;

import lombok.Data;
import org.smartledge.ai.knowledge.indexing.port.IndexingConfigurationPort;

@Data
public class KnowledgeBaseIndexingOptions {

    private ChunkOptions chunk = new ChunkOptions();

    private GraphRagBuildOptions graphRag = new GraphRagBuildOptions();

    private RaptorBuildOptions raptor = new RaptorBuildOptions();

    public static KnowledgeBaseIndexingOptions fromDefaults(IndexingConfigurationPort configuration,
                                                            Integer raptorMaxClusterSize,
                                                            Integer raptorMaxLevels,
                                                            Boolean raptorLlmSummaryEnabled,
                                                            Double raptorSummaryQualityFloor) {
        KnowledgeBaseIndexingOptions options = new KnowledgeBaseIndexingOptions();
        IndexingConfigurationPort.ChunkDefaults chunk = configuration == null
            ? IndexingConfigurationPort.ChunkDefaults.defaults()
            : configuration.currentChunkDefaults();
        if (chunk == null) {
            chunk = IndexingConfigurationPort.ChunkDefaults.defaults();
        }
        options.getChunk().setChildRecursiveMaxChars(defaultInteger(chunk.childRecursiveMaxChars(), 800));
        options.getChunk().setChildRecursiveOverlapChars(defaultInteger(chunk.childRecursiveOverlapChars(), 120));
        options.getChunk().setChildSemanticMaxChars(defaultInteger(chunk.childSemanticMaxChars(), 700));
        options.getChunk().setChildSemanticMinChars(defaultInteger(chunk.childSemanticMinChars(), 240));
        options.getChunk().setChildSemanticSimilarityThreshold(defaultDouble(chunk.childSemanticSimilarityThreshold(), 0.18D));
        options.getChunk().setParentBlockMaxChars(defaultInteger(chunk.parentBlockMaxChars(), 2200));
        options.getChunk().setParentBlockOverlapChars(defaultInteger(chunk.parentBlockOverlapChars(), 180));
        options.getChunk().setParentSemanticMaxChars(defaultInteger(chunk.parentSemanticMaxChars(), 1600));
        options.getChunk().setParentSemanticMinChars(defaultInteger(chunk.parentSemanticMinChars(), 480));

        options.getGraphRag().setGraphRagBuildEnabled(Boolean.TRUE);

        options.getRaptor().setRaptorBuildEnabled(Boolean.TRUE);
        options.getRaptor().setRaptorMaxClusterSize(defaultInteger(raptorMaxClusterSize, 6));
        options.getRaptor().setRaptorMaxLevels(defaultInteger(raptorMaxLevels, 3));
        options.getRaptor().setRaptorLlmSummaryEnabled(defaultBoolean(raptorLlmSummaryEnabled, true));
        options.getRaptor().setRaptorSummaryQualityFloor(defaultDouble(raptorSummaryQualityFloor, 0.42D));
        return options;
    }

    public static KnowledgeBaseIndexingOptions defaults() {
        return fromDefaults(null, 6, 3, true, 0.42D);
    }

    private static Integer defaultInteger(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static Double defaultDouble(Double value, Double defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static Boolean defaultBoolean(Boolean value, Boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    @Data
    public static class ChunkOptions {

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
    public static class GraphRagBuildOptions {

        private Boolean graphRagBuildEnabled;
    }

    @Data
    public static class RaptorBuildOptions {

        private Boolean raptorBuildEnabled;

        private Integer raptorMaxClusterSize;

        private Integer raptorMaxLevels;

        private Boolean raptorLlmSummaryEnabled;

        private Double raptorSummaryQualityFloor;
    }
}
