package org.smartledge.ai.manage.support;

public class ChunkingProfileException extends IllegalStateException {
    private final String code;
    private final String unitId;

    public ChunkingProfileException(String code, String unitId) { this(code, unitId, null); }
    public ChunkingProfileException(String code, String unitId, Throwable cause) {
        super(code + (unitId == null ? "" : ": " + unitId), cause);
        this.code = code;
        this.unitId = unitId;
    }
    public String getCode() { return code; }
    public String getUnitId() { return unitId; }
}
