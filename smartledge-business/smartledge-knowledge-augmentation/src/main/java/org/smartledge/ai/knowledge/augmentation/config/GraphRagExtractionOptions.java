package org.smartledge.ai.knowledge.augmentation.config;

import lombok.Data;

/**
 * LLM GraphRAG extraction limits projected from the composition root.
 */
@Data
public class GraphRagExtractionOptions {

    // One source segment per model call. At 3 the segment gate became the sole binding
    // constraint once batchTokenLimit rose, collapsing batch count and with it total
    // candidate output (output tracks call count, not input text volume).
    private int batchChunkLimit = 1;

    // Must stay in sync with SystemConfigSnapshot.GraphRagExtraction#inputTokenBudget.
    // The system prompt is serialised into every batch's cost estimate, so this ceiling
    // bounds source text per call, not just payload size. Measured with the real
    // estimator (byte-level unmerged BPE, ~3 tokens per CJK char): the system prompt
    // alone costs ~3027, so a full maxUnitTextChars=420 unit needs >= 4287. Below that
    // the planner silently bisects units mid-sentence. 4600 is the smallest safe value,
    // not a tuned one, and it uses only ~19% of the fits() ceiling of 11264
    // (contextTokens - outputReserve - promptReserve, see rag-tools.yaml).
    private int inputTokenBudget = 4600;

    private int modelContextTokens = 16384;

    private int outputReserve = 4096;

    private int promptReserve = 1024;

    private int modelResponseMaxBytes = 1048576;

    private int maxEntityNameChars = 500;

    private long maxDocumentBytes = 4000000L;

    private int maxQuoteChars = 180;

    private int maxReasonChars = 240;

    private int maxUnitTextChars = 420;
}
