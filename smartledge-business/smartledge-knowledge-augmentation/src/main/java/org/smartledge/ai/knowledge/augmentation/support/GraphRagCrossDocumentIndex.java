package org.smartledge.ai.knowledge.augmentation.support;

import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgEntity;
import org.smartledge.ai.knowledge.augmentation.data.SuperAgentKgRelation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record GraphRagCrossDocumentIndex(Map<Long, CanonicalEntityGroup> canonicalGroupByEntityId,
                                         Map<Long, RelationGroup> relationGroupByRelationId,
                                         Map<String, CrossDocumentCommunity> communityByKey,
                                         Map<String, CrossDocumentCommunity> communityByRelationGroupKey) {

    public static GraphRagCrossDocumentIndex empty() {
        return new GraphRagCrossDocumentIndex(Map.of(), Map.of(), Map.of(), Map.of());
    }

    public GraphRagCrossDocumentIndex(Map<Long, CanonicalEntityGroup> canonicalGroupByEntityId,
                                      Map<Long, RelationGroup> relationGroupByRelationId) {
        this(canonicalGroupByEntityId, relationGroupByRelationId, Map.of(), Map.of());
    }

    public CanonicalEntityGroup canonicalGroupOf(Long entityId) {
        return entityId == null ? null : canonicalGroupByEntityId.get(entityId);
    }

    public RelationGroup relationGroupOf(Long relationId) {
        return relationId == null ? null : relationGroupByRelationId.get(relationId);
    }

    public CrossDocumentCommunity communityOfRelationGroup(String relationGroupKey) {
        return relationGroupKey == null ? null : communityByRelationGroupKey.get(relationGroupKey);
    }

    public boolean hasCanonicalGroups() {
        return canonicalGroupByEntityId != null && !canonicalGroupByEntityId.isEmpty();
    }

    public boolean hasRelationGroups() {
        return relationGroupByRelationId != null && !relationGroupByRelationId.isEmpty();
    }

    public boolean hasCommunities() {
        return communityByKey != null && !communityByKey.isEmpty();
    }

    public Map<String, CanonicalEntityGroup> distinctCanonicalGroups() {
        LinkedHashMap<String, CanonicalEntityGroup> result = new LinkedHashMap<>();
        if (canonicalGroupByEntityId == null) {
            return result;
        }
        for (CanonicalEntityGroup group : canonicalGroupByEntityId.values()) {
            if (group != null) {
                result.putIfAbsent(group.key(), group);
            }
        }
        return result;
    }

    public Map<String, RelationGroup> distinctRelationGroups() {
        LinkedHashMap<String, RelationGroup> result = new LinkedHashMap<>();
        if (relationGroupByRelationId == null) {
            return result;
        }
        for (RelationGroup group : relationGroupByRelationId.values()) {
            if (group != null) {
                result.putIfAbsent(group.key(), group);
            }
        }
        return result;
    }

    public record CanonicalEntityGroup(String key,
                                       String name,
                                       String entityType,
                                       Set<Long> entityIds,
                                       Set<Long> documentIds,
                                       Set<Long> taskIds,
                                       List<String> variants,
                                       double rankScore,
                                       QualityProfile qualityProfile,
                                       RankProfile rankProfile) {

        public CanonicalEntityGroup {
            qualityProfile = qualityProfile == null ? QualityProfile.empty() : qualityProfile;
            rankProfile = rankProfile == null ? RankProfile.empty() : rankProfile;
        }

        public CanonicalEntityGroup(String key,
                                    String name,
                                    String entityType,
                                    Set<Long> entityIds,
                                    Set<Long> documentIds,
                                    Set<Long> taskIds,
                                    List<String> variants,
                                    double rankScore,
                                    QualityProfile qualityProfile) {
            this(key, name, entityType, entityIds, documentIds, taskIds, variants, rankScore, qualityProfile, RankProfile.empty());
        }
    }

    public record RelationGroup(String key,
                                String sourceGroupKey,
                                String targetGroupKey,
                                String relationType,
                                Set<Long> relationIds,
                                Set<Long> evidenceIds,
                                Set<Long> documentIds,
                                Map<Long, Integer> evidenceCountByRelationId,
                                double rankScore,
                                QualityProfile qualityProfile,
                                RankProfile rankProfile) {

        public RelationGroup {
            qualityProfile = qualityProfile == null ? QualityProfile.empty() : qualityProfile;
            rankProfile = rankProfile == null ? RankProfile.empty() : rankProfile;
        }

        public RelationGroup(String key,
                             String sourceGroupKey,
                             String targetGroupKey,
                             String relationType,
                             Set<Long> relationIds,
                             Set<Long> evidenceIds,
                             Set<Long> documentIds,
                             Map<Long, Integer> evidenceCountByRelationId,
                             double rankScore,
                             QualityProfile qualityProfile) {
            this(key, sourceGroupKey, targetGroupKey, relationType, relationIds, evidenceIds, documentIds,
                evidenceCountByRelationId, rankScore, qualityProfile, RankProfile.empty());
        }

        public int relationCount() {
            return relationIds == null ? 0 : relationIds.size();
        }

        public int evidenceCount() {
            if (evidenceCountByRelationId != null && !evidenceCountByRelationId.isEmpty()) {
                return evidenceCountByRelationId.values().stream()
                    .mapToInt(value -> value == null ? 0 : value)
                    .sum();
            }
            return evidenceIds == null ? 0 : evidenceIds.size();
        }

        public int documentCount() {
            return documentIds == null ? 0 : documentIds.size();
        }
    }

    public record QualityProfile(double score,
                                 List<String> qualityReasons,
                                 List<String> noiseReasons) {

        public static QualityProfile empty() {
            return new QualityProfile(0D, List.of(), List.of());
        }
    }

    public record CrossDocumentCommunity(Long id,
                                         String key,
                                         String title,
                                         String summary,
                                         Set<String> canonicalGroupKeys,
                                         Set<String> relationGroupKeys,
                                         Set<Long> evidenceIds,
                                         Set<Long> documentIds,
                                         ReportProfile reportProfile,
                                         double rankScore,
                                         QualityProfile qualityProfile,
                                         RankProfile rankProfile) {

        public CrossDocumentCommunity {
            reportProfile = reportProfile == null ? ReportProfile.empty() : reportProfile;
            qualityProfile = qualityProfile == null ? QualityProfile.empty() : qualityProfile;
            rankProfile = rankProfile == null ? RankProfile.empty() : rankProfile;
        }

        public CrossDocumentCommunity(Long id,
                                      String key,
                                      String title,
                                      String summary,
                                      Set<String> canonicalGroupKeys,
                                      Set<String> relationGroupKeys,
                                      Set<Long> evidenceIds,
                                      Set<Long> documentIds,
                                      double rankScore,
                                      QualityProfile qualityProfile,
                                      RankProfile rankProfile) {
            this(id, key, title, summary, canonicalGroupKeys, relationGroupKeys, evidenceIds, documentIds,
                ReportProfile.empty(), rankScore, qualityProfile, rankProfile);
        }

        public int entityCount() {
            return canonicalGroupKeys == null ? 0 : canonicalGroupKeys.size();
        }

        public int relationGroupCount() {
            return relationGroupKeys == null ? 0 : relationGroupKeys.size();
        }

        public int evidenceCount() {
            return evidenceIds == null ? 0 : evidenceIds.size();
        }

        public int documentCount() {
            return documentIds == null ? 0 : documentIds.size();
        }
    }

    public record ReportProfile(String strategy,
                                List<String> coreEntityNames,
                                List<String> keyRelationTypes,
                                List<String> evidenceBoundaries,
                                List<String> cannotInfer,
                                double qualityScore,
                                List<String> qualityReasons) {

        public static ReportProfile empty() {
            return new ReportProfile("", List.of(), List.of(), List.of(), List.of(), 0D, List.of());
        }
    }

    public record RankProfile(double pagerank,
                              double rankBoost,
                              int rankPosition,
                              int degree,
                              int inDegree,
                              int outDegree,
                              double weightedDegree) {

        public static RankProfile empty() {
            return new RankProfile(0D, 0D, 0, 0, 0, 0, 0D);
        }
    }

    public record RelationEndpoint(SuperAgentKgRelation relation,
                                   SuperAgentKgEntity source,
                                   SuperAgentKgEntity target) {
    }
}
