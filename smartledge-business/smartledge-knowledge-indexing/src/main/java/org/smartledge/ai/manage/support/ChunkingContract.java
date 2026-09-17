package org.smartledge.ai.manage.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.smartledge.ai.manage.data.SuperAgentDocumentTask;
import org.smartledge.ai.manage.data.SuperAgentDocumentStrategyStep;
import java.util.List;

import java.util.Objects;

public record ChunkingContract(String schemaVersion, String recommendedProfile, String profile,
                               String ruleVersion, String reason, Long sourceParseTaskId,
                               String sourceSha256, Budget budget, boolean confirmed) {
    public static final String SCHEMA = "chunking-contract.v1";
    public static final String QA = "QA_PAIR";
    public static final String GENERIC = "GENERIC";
    public static final String QA_RULE = "qa-pair.v1";

    public ChunkingContract {
        if (!SCHEMA.equals(schemaVersion) || !validProfile(profile) || !validProfile(recommendedProfile)
            || !(QA.equals(profile) ? QA_RULE : "generic.v1").equals(ruleVersion)
            || sourceParseTaskId == null || sourceParseTaskId <= 0 || budget == null) {
            throw new ChunkingProfileException("PROFILE_CONTRACT_INVALID", null);
        }
        if (QA.equals(profile) && (sourceSha256 == null || !sourceSha256.matches("[0-9a-f]{64}"))) {
            throw new ChunkingProfileException("PROFILE_SOURCE_MISSING", null);
        }
    }

    private static boolean validProfile(String profile) {
        return QA.equals(profile) || GENERIC.equals(profile);
    }

    public boolean qa() { return QA.equals(profile); }

    public ChunkingContract confirm(String selected) {
        if (!validProfile(selected) || (QA.equals(selected) && !QA.equals(recommendedProfile))) {
            throw new ChunkingProfileException("PROFILE_NOT_APPLICABLE", null);
        }
        return new ChunkingContract(schemaVersion, recommendedProfile, selected,
            QA.equals(selected) ? QA_RULE : "generic.v1", reason, sourceParseTaskId, sourceSha256, budget, true);
    }

    public void requireSource(Long parseTaskId) {
        if (!Objects.equals(sourceParseTaskId, parseTaskId)) {
            throw new ChunkingProfileException("PROFILE_PARSE_REVISION_STALE", null);
        }
    }

    public void requireStrategies(List<SuperAgentDocumentStrategyStep> steps) {
        if (!qa()) { return; }
        if (steps.size() != 2 || steps.stream().filter(step -> "PARENT".equals(step.getPipelineType())
            && Integer.valueOf(1).equals(step.getStrategyType())).count() != 1
            || steps.stream().filter(step -> "CHILD".equals(step.getPipelineType())
            && Integer.valueOf(2).equals(step.getStrategyType())).count() != 1) {
            throw new ChunkingProfileException("PROFILE_STRATEGY_INCOMPATIBLE", null);
        }
    }

    public void freezeTask(SuperAgentDocumentTask task, ObjectMapper mapper) {
        requireConfirmed();
        requireSource(task.getSourceParseTaskId());
        try {
            ObjectNode ext = task.getExtJson() == null ? mapper.createObjectNode()
                : (ObjectNode) mapper.readTree(task.getExtJson());
            ext.set("chunkingContract", mapper.valueToTree(this));
            task.setExtJson(mapper.writeValueAsString(ext));
        } catch (Exception exception) {
            throw new ChunkingProfileException("PROFILE_TASK_SNAPSHOT_INVALID", null, exception);
        }
    }

    public void requireFrozenTask(SuperAgentDocumentTask task, ObjectMapper mapper) {
        requireConfirmed();
        requireSource(task.getSourceParseTaskId());
        try {
            var snapshot = mapper.readTree(task.getExtJson()).get("chunkingContract");
            if (snapshot == null || !equals(read(snapshot.toString(), mapper))) {
                throw new ChunkingProfileException("PROFILE_TASK_SNAPSHOT_DRIFT", null);
            }
        } catch (ChunkingProfileException exception) { throw exception; }
        catch (Exception exception) {
            throw new ChunkingProfileException("PROFILE_TASK_SNAPSHOT_INVALID", null, exception);
        }
    }

    public void requireConfirmed() {
        if (!confirmed) { throw new ChunkingProfileException("PROFILE_NOT_CONFIRMED", null); }
    }

    public static ChunkingContract read(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) { return null; }
        try {
            return mapper.readerFor(ChunkingContract.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(json);
        } catch (Exception exception) {
            throw new ChunkingProfileException("PROFILE_CONTRACT_INVALID", null, exception);
        }
    }

    public String json(ObjectMapper mapper) {
        try { return mapper.writeValueAsString(this); }
        catch (Exception exception) { throw new IllegalStateException("Cannot serialize chunking contract", exception); }
    }

    /** Application byte ceiling; this is not a model tokenizer or token-limit claim. */
    public record Budget(int parentMaxChars, int childMaxChars, int overlapChars, int maxInputBytes) {
        public Budget {
            if (parentMaxChars < 1 || childMaxChars < 1 || overlapChars < 0
                || overlapChars >= childMaxChars || maxInputBytes < 1) {
                throw new ChunkingProfileException("PROFILE_BUDGET_INVALID", null);
            }
        }
    }
}
