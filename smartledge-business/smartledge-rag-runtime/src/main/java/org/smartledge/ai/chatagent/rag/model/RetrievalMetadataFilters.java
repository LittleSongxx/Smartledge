package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Controlled metadata filters extracted once by the plan assembler. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalMetadataFilters {

    @Builder.Default
    private List<String> documentNameHints = new ArrayList<>();

    @Builder.Default
    private List<String> sectionPathHints = new ArrayList<>();

    @Builder.Default
    private List<String> yearHints = new ArrayList<>();

    @Builder.Default
    private List<String> entityHints = new ArrayList<>();

    /**
     * 已授权的文档范围提示（产品级消歧）：查询理解点名产品且通过原问题 grounding、
     * 置信度与文档名唯一匹配授权后，指向目标文档 id。空列表 = 未授权，不收窄。
     * 这不是对 RouteScopeAuthorizationMode 的重新解释——授权范围不变，
     * 收窄以索引上的结构化过滤（document_id IN 交集）形式叠加并全程可审计。
     */
    @Builder.Default
    private List<Long> documentIdHints = new ArrayList<>();

    /** 消歧授权结论（含未授权原因），供观测与对账。 */
    private String documentFocusReason;
}
