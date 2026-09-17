package org.smartledge.ai.chatagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Deterministic rank metrics. Compares stable identities only; does not guess citations.
 * Default {@code rag-gold.v1} identities are synthetic scorer keys, not live index IDs.
 * Live probe or archive lists must enter through {@link GoldReplayDump} or {@link GoldReplaySupport}.
 * Offline faithfulness lives in {@code rag_tools.eval.offline_faithfulness} and never writes [n].
 */
public final class ReplayableGoldSetScorer {

    public static final String CLASSPATH_RESOURCE = "eval/rag-gold.v1.json";

    private final ObjectMapper mapper;

    public ReplayableGoldSetScorer(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ReplayableGoldSet loadDefault() {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing gold set: " + CLASSPATH_RESOURCE);
            }
            return load(input);
        }
        catch (IOException exception) {
            throw new IllegalStateException("failed to read gold set", exception);
        }
    }

    public ReplayableGoldSet load(InputStream input) throws IOException {
        ReplayableGoldSet set = mapper.readValue(input, ReplayableGoldSet.class);
        if (!"rag-gold.v1".equals(set.schemaVersion())) {
            throw new IllegalArgumentException("unsupported gold schema: " + set.schemaVersion());
        }
        return set;
    }

    public GoldReplayDump loadReplay(InputStream input) throws IOException {
        GoldReplayDump dump = mapper.readValue(input, GoldReplayDump.class);
        if (!GoldReplayDump.SCHEMA_VERSION.equals(dump.schemaVersion())) {
            throw new IllegalArgumentException("unsupported gold replay schema: " + dump.schemaVersion());
        }
        return dump;
    }

    public List<GoldScore> score(ReplayableGoldSet set, Function<ReplayableGoldSet.GoldCase, List<String>> retriever) {
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(retriever, "retriever");
        List<GoldScore> scores = new ArrayList<>();
        for (ReplayableGoldSet.GoldCase goldCase : set.cases()) {
            List<String> retrieved = retriever.apply(goldCase);
            scores.add(scoreCase(goldCase, retrieved == null ? List.of() : retrieved));
        }
        return List.copyOf(scores);
    }

    public List<GoldScore> score(ReplayableGoldSet set, GoldReplayDump dump) {
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(dump, "dump");
        Set<String> known = new LinkedHashSet<>();
        for (ReplayableGoldSet.GoldCase goldCase : set.cases()) {
            known.add(goldCase.id());
        }
        for (String caseId : dump.retrieved().keySet()) {
            if (!known.contains(caseId)) {
                throw new IllegalArgumentException("unknown gold case in replay dump: " + caseId);
            }
        }
        return score(set, goldCase -> dump.retrieved().getOrDefault(goldCase.id(), List.of()));
    }

    /** 非空 scope 用例的 mean Recall@K；空 scope 不进这个平均。 */
    public double meanRecall(List<GoldScore> scores) {
        if (scores == null || scores.isEmpty()) {
            return 0D;
        }
        return scores.stream()
            .filter(score -> score.relevantCount() > 0)
            .mapToDouble(GoldScore::recallAtK)
            .average()
            .orElse(0D);
    }

    public double meanNdcg(List<GoldScore> scores) {
        if (scores == null || scores.isEmpty()) {
            return 0D;
        }
        return scores.stream()
            .filter(score -> score.relevantCount() > 0)
            .mapToDouble(GoldScore::ndcgAtK)
            .average()
            .orElse(0D);
    }

    public double meanMrr(List<GoldScore> scores) {
        if (scores == null || scores.isEmpty()) {
            return 0D;
        }
        return scores.stream()
            .filter(score -> score.relevantCount() > 0)
            .mapToDouble(GoldScore::mrr)
            .average()
            .orElse(0D);
    }

    public double emptyScopeCorrectness(List<GoldScore> scores) {
        List<GoldScore> empty = scores == null ? List.of() : scores.stream()
            .filter(score -> score.relevantCount() == 0)
            .toList();
        if (empty.isEmpty()) {
            return 1D;
        }
        return empty.stream().mapToDouble(GoldScore::emptyScopeCorrectness).average().orElse(0D);
    }

    public GoldScore scoreCase(ReplayableGoldSet.GoldCase goldCase, List<String> retrievedIdentities) {
        List<String> relevant = goldCase.relevantIdentities();
        List<String> retrieved = retrievedIdentities == null ? List.of() : retrievedIdentities.stream()
            .filter(Objects::nonNull)
            .toList();
        if (relevant.isEmpty()) {
            boolean empty = retrieved.isEmpty();
            return new GoldScore(goldCase.id(), 0D, empty ? 1D : 0D, 0D, 0D, empty ? 0 : 1, 0, goldCase.k());
        }
        Set<String> relevantSet = new LinkedHashSet<>(relevant);
        Set<String> window = new LinkedHashSet<>();
        retrieved.stream().limit(goldCase.k()).forEach(window::add);
        int hits = 0;
        for (String identity : relevant) {
            if (window.contains(identity)) {
                hits++;
            }
        }
        double recall = hits / (double) relevant.size();
        return new GoldScore(
            goldCase.id(),
            recall,
            0D,
            ndcgAtK(retrieved, relevantSet, goldCase.k()),
            mrr(retrieved, relevantSet, goldCase.k()),
            hits,
            relevant.size(),
            goldCase.k()
        );
    }

    private static double ndcgAtK(List<String> retrieved, Set<String> relevant, int k) {
        double dcg = 0D;
        int limit = Math.min(k, retrieved.size());
        for (int index = 0; index < limit; index++) {
            if (relevant.contains(retrieved.get(index))) {
                dcg += 1D / (Math.log(index + 2) / Math.log(2));
            }
        }
        int idealHits = Math.min(k, relevant.size());
        double idcg = 0D;
        for (int index = 0; index < idealHits; index++) {
            idcg += 1D / (Math.log(index + 2) / Math.log(2));
        }
        return idcg == 0D ? 0D : dcg / idcg;
    }

    private static double mrr(List<String> retrieved, Set<String> relevant, int k) {
        int limit = Math.min(k, retrieved.size());
        for (int index = 0; index < limit; index++) {
            if (relevant.contains(retrieved.get(index))) {
                return 1D / (index + 1D);
            }
        }
        return 0D;
    }

    public record GoldScore(
        String caseId,
        double recallAtK,
        double emptyScopeCorrectness,
        double ndcgAtK,
        double mrr,
        int hits,
        int relevantCount,
        int k
    ) {
    }
}
