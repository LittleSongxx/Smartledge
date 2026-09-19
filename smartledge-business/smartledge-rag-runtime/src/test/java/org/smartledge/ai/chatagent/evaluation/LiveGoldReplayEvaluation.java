package org.smartledge.ai.chatagent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.fail;

/**
 * 线上金标评测 runner：对真实部署跑检索探针并按 rag-gold.live-embodied.v1 打分。
 *
 * <p>默认不运行（CI 无系统属性时跳过）。执行示例：</p>
 * <pre>
 * mvn -pl smartledge-business/smartledge-rag-runtime test \
 *   -Dtest=LiveGoldReplayEvaluation \
 *   -Dlive.eval.baseUrl=https://smartledge.cn \
 *   -Dlive.eval.password=***        # 或 -Dlive.eval.token=***
 * </pre>
 * <p>节流默认 6.6s/次（探针限频 10 次/分钟/管理员）；`-Dlive.eval.rerank=false` 跑 rerank 关闭对照轮。</p>
 *
 * <p>粒度归一化：金标锚定 CHUNK 级 identity；引擎返回的 {@code PARENT:doc:pb} 展开为其子 CHUNK、
 * {@code KG_QUOTE:*:CHUNK:cid} 折算回 CHUNK（同一证据跨度只计一次），映射来自
 * {@code eval/rag-gold.live-embodied.v1.index.json}。相关性判定不受此影响，只消除粒度错配。</p>
 */
@EnabledIfSystemProperty(named = "live.eval.baseUrl", matches = ".+")
class LiveGoldReplayEvaluation {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GOLD_RESOURCE = "eval/rag-gold.live-embodied.v1.json";
    private static final String INDEX_RESOURCE = "eval/rag-gold.live-embodied.v1.index.json";
    private static final String PROBE_QUERY_SCHEMA = "retrieval-probe-query.v1";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Test
    void runLiveGoldEvaluation() throws Exception {
        String baseUrl = requiredProperty("live.eval.baseUrl").replaceAll("/+$", "");
        String knowledgeBaseId = optionalProperty("live.eval.kbId", "2526644082445484032");
        boolean rerankEnabled = !"false".equalsIgnoreCase(optionalProperty("live.eval.rerank", "true"));
        long intervalMillis = Long.parseLong(optionalProperty("live.eval.intervalMillis", "6600"));
        String experimentPrefix = optionalProperty("live.eval.experiment", "live-embodied-v1" + (rerankEnabled ? "" : "-norere"));

        String token = optionalProperty("live.eval.token", null);
        if (token == null) {
            token = login(baseUrl, optionalProperty("live.eval.username", "admin"), requiredProperty("live.eval.password"));
        }

        ReplayableGoldSetScorer scorer = new ReplayableGoldSetScorer(MAPPER);
        ReplayableGoldSet gold = scorer.load(openInput(optionalProperty("live.eval.goldPath", null), GOLD_RESOURCE));
        JsonNode index = MAPPER.readTree(openInput(optionalProperty("live.eval.indexPath", null), INDEX_RESOURCE));
        Map<String, List<String>> parents = readParents(index.get("parents"));
        Map<String, String> chunkDoc = readChunkDoc(index.get("chunkDoc"));
        Map<String, List<String>> summaries = readParents(index.get("summaries"));
        Map<String, String> kgEvidence = readChunkDoc(index.get("kgEvidence"));

        Map<String, List<String>> retrievedByCase = new LinkedHashMap<>();
        for (ReplayableGoldSet.GoldCase goldCase : gold.cases()) {
            List<String> identities = probeOnce(baseUrl, token, experimentPrefix, goldCase, knowledgeBaseId, rerankEnabled);
            retrievedByCase.put(goldCase.id(), normalizeIdentities(identities, parents, chunkDoc, summaries, kgEvidence));
            System.out.printf("[live-eval] %-24s -> %d source identities%n", goldCase.id(),
                retrievedByCase.get(goldCase.id()).size());
            Thread.sleep(intervalMillis);
        }

        GoldReplayDump dump = new GoldReplayDump(GoldReplayDump.SCHEMA_VERSION, "live run " + experimentPrefix, retrievedByCase);
        List<ReplayableGoldSetScorer.GoldScore> scores = scorer.score(gold, dump);
        ObjectNode report = buildReport(experimentPrefix, baseUrl, rerankEnabled, scores);
        Path out = Path.of(optionalProperty("live.eval.outPath", "target/live-gold-report.json"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("[live-eval] meanRecall=" + scorer.meanRecall(scores)
            + " meanNdcg=" + scorer.meanNdcg(scores)
            + " meanMrr=" + scorer.meanMrr(scores)
            + " emptyScopeCorrectness=" + scorer.emptyScopeCorrectness(scores));
        System.out.println("[live-eval] report -> " + out.toAbsolutePath());
    }

    // ---------- 纯逻辑：identity 归一化（无网络，另有单测锁定） ----------

    /** PARENT 展开为子 CHUNK、KG_QUOTE 折算回 CHUNK、SUMMARY:RAPTOR 展开为源 CHUNK，其余原样；去重保序。 */
    static List<String> normalizeIdentities(List<String> identities,
                                            Map<String, List<String>> parents,
                                            Map<String, String> chunkDoc,
                                            Map<String, List<String>> summaries,
                                            Map<String, String> kgEvidence) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String identity : identities) {
            if (identity == null || identity.isBlank()) {
                continue;
            }
            if (identity.startsWith("PARENT:")) {
                List<String> children = parents.get(identity);
                if (children != null && !children.isEmpty()) {
                    normalized.addAll(children);
                    continue;
                }
                normalized.add(identity);
                continue;
            }
            if (identity.startsWith("SUMMARY:RAPTOR:")) {
                // raptor 通道返回的摘要节点与金标锚定的节点可能层级不同：
                // 折算到该节点的源 chunk，命中其中任一即视为覆盖同一证据跨度（与 PARENT 同一宽容口径）。
                List<String> sources = summaries.get(identity);
                if (sources != null && !sources.isEmpty()) {
                    normalized.addAll(sources);
                    continue;
                }
                normalized.add(identity);
                continue;
            }
            if (identity.startsWith("KG_QUOTE:") && identity.contains(":CHUNK:")) {
                String chunkId = identity.substring(identity.lastIndexOf(":CHUNK:") + ":CHUNK:".length());
                String docId = chunkDoc.get(chunkId);
                if (docId != null) {
                    normalized.add("CHUNK:" + docId + ":" + chunkId);
                    continue;
                }
            }
            if (identity.startsWith("SUMMARY:KG:")) {
                // 图谱通道的文档级社区摘要锚定在一条 kg 证据上（communityKey 即证据 id）：
                // 折算回该证据的源 chunk，与 KG_QUOTE 同一宽容口径。
                String evidenceId = identity.substring("SUMMARY:KG:".length());
                String chunkId = kgEvidence.get(evidenceId);
                String docId = chunkId == null ? null : chunkDoc.get(chunkId);
                if (docId != null) {
                    normalized.add("CHUNK:" + docId + ":" + chunkId);
                    continue;
                }
                normalized.add(identity);
                continue;
            }
            normalized.add(identity);
        }
        return List.copyOf(normalized);
    }

    // ---------- HTTP ----------

    private String login(String baseUrl, String username, String password) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("username", username);
        body.put("password", password);
        JsonNode data = post(baseUrl + "/admin/auth/login", null, body);
        JsonNode token = data.path("token");
        if (token.isTextual() && !token.asText().isBlank()) {
            return token.asText();
        }
        throw new IllegalStateException("登录响应缺少 token（账号/密码或 -Dlive.eval.token 配置有误）");
    }

    private List<String> probeOnce(String baseUrl, String token, String experimentPrefix,
                                   ReplayableGoldSet.GoldCase goldCase, String knowledgeBaseId,
                                   boolean rerankEnabled) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("schemaVersion", PROBE_QUERY_SCHEMA);
        body.put("experimentId", experimentPrefix + ":" + goldCase.id());
        body.put("query", goldCase.query());
        body.put("selectionMode", "SELECTED");
        ArrayNode kbIds = body.putArray("knowledgeBaseIds");
        kbIds.add(knowledgeBaseId);
        if (!rerankEnabled) {
            body.putObject("overrides").put("rerankEnabled", false);
        }
        String overridesJson = optionalProperty("live.eval.overrides", null);
        if (overridesJson != null && !overridesJson.isBlank()) {
            JsonNode extra = MAPPER.readTree(overridesJson);
            if (!extra.isObject()) {
                throw new IllegalArgumentException("-Dlive.eval.overrides 必须是 JSON 对象，如 '{\"enabledChannels\":[\"vector\",\"keyword\"]}'");
            }
            ObjectNode overrides = body.has("overrides") ? (ObjectNode) body.get("overrides") : body.putObject("overrides");
            extra.fields().forEachRemaining(entry -> overrides.set(entry.getKey(), entry.getValue()));
        }
        JsonNode data = post(baseUrl + "/manage/evaluation/retrieval/probe", token, body);
        List<String> identities = new ArrayList<>();
        for (JsonNode subQuestion : data.path("subQuestions")) {
            for (JsonNode identity : subQuestion.path("sourceCandidateIdentities")) {
                if (identity.isTextual()) {
                    identities.add(identity.asText());
                }
            }
        }
        return identities;
    }

    private JsonNode post(String url, String token, ObjectNode body) throws IOException, InterruptedException {
        // 探针限频是固定窗口；压线时单次 12s 退避重试，避免整轮因偶发限流失败。
        for (int attempt = 1; attempt <= 2; attempt++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429 && attempt == 1) {
                Thread.sleep(12_000L);
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url + " body=" + clip(response.body()));
            }
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode data = root.path("data");
            return data.isMissingNode() ? root : data;
        }
        throw new IllegalStateException("rate limited twice by " + url);
    }

    // ---------- 装配 ----------

    private ObjectNode buildReport(String experiment, String baseUrl, boolean rerankEnabled,
                                   List<ReplayableGoldSetScorer.GoldScore> scores) {
        ReplayableGoldSetScorer scorer = new ReplayableGoldSetScorer(MAPPER);
        ObjectNode report = MAPPER.createObjectNode();
        report.put("schemaVersion", "live-gold-report.v1");
        report.put("generatedAt", Instant.now().toString());
        report.put("baseUrl", baseUrl);
        report.put("experiment", experiment);
        report.put("rerankEnabled", rerankEnabled);
        report.put("caseCount", scores.size());
        ObjectNode summary = report.putObject("summary");
        summary.put("meanRecallAtK", scorer.meanRecall(scores));
        summary.put("meanNdcgAtK", scorer.meanNdcg(scores));
        summary.put("meanMrrAtK", scorer.meanMrr(scores));
        summary.put("emptyScopeCorrectness", scorer.emptyScopeCorrectness(scores));
        ArrayNode caseNodes = report.putArray("cases");
        scores.stream()
            .sorted((left, right) -> Double.compare(left.recallAtK(), right.recallAtK()))
            .forEach(score -> {
                ObjectNode node = caseNodes.addObject();
                node.put("caseId", score.caseId());
                node.put("recallAtK", score.recallAtK());
                node.put("ndcgAtK", score.ndcgAtK());
                node.put("mrr", score.mrr());
                node.put("hits", score.hits());
                node.put("relevantCount", score.relevantCount());
                node.put("k", score.k());
                node.put("emptyScopeCorrectness", score.emptyScopeCorrectness());
            });
        return report;
    }

    private Map<String, List<String>> readParents(JsonNode parentsNode) {
        Map<String, List<String>> parents = new LinkedHashMap<>();
        if (parentsNode != null) {
            parentsNode.fields().forEachRemaining(entry ->
                parents.put(entry.getKey(), new ArrayList<>()));
            parentsNode.fields().forEachRemaining(entry -> {
                List<String> children = parents.get(entry.getKey());
                entry.getValue().forEach(child -> children.add(child.asText()));
            });
        }
        return parents;
    }

    private Map<String, String> readChunkDoc(JsonNode chunkDocNode) {
        Map<String, String> chunkDoc = new LinkedHashMap<>();
        if (chunkDocNode != null) {
            chunkDocNode.fields().forEachRemaining(entry -> chunkDoc.put(entry.getKey(), entry.getValue().asText()));
        }
        return chunkDoc;
    }

    private java.io.InputStream openInput(String path, String classpathResource) throws IOException {
        if (path != null && !path.isBlank()) {
            return Files.newInputStream(Path.of(path));
        }
        java.io.InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathResource);
        if (input == null) {
            fail("missing eval resource: " + classpathResource);
        }
        return input;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            fail("缺少系统属性 -D" + name);
        }
        return value;
    }

    private static String optionalProperty(String name, String fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String clip(String body) {
        return body == null ? "" : body.substring(0, Math.min(body.length(), 400));
    }
}
