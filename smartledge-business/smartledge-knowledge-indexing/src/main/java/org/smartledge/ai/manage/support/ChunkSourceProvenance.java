package org.smartledge.ai.manage.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

public record ChunkSourceProvenance(String schemaVersion, String pairId, Long sourceParseTaskId,
                                    String sourceSha256, String ruleVersion, Source question,
                                    List<Source> answers) {
    public ChunkSourceProvenance { answers = List.copyOf(answers); }
    public record Source(Long blockId, String nodeId, int startByte, int endByte) { }
    public String json(ObjectMapper mapper) {
        try { return mapper.writeValueAsString(this); }
        catch (Exception exception) { throw new IllegalStateException("Cannot serialize chunk provenance", exception); }
    }
}
