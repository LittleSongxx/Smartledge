package org.smartledge.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上一轮最终引用形成的证据锚点，只用于追问指代和检索范围限定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceAnchor {

    private Long documentId;

    private String documentName;

    private Long taskId;

    private Long knowledgeBaseId;

    private String knowledgeBaseName;

    private Long structureNodeId;

    private String sectionPath;

    private String canonicalPath;

    private Integer itemIndex;

    private Long parentBlockId;

    private Long chunkId;

    private String sourceType;

    private String channel;

    private String snippet;

    private Double score;
}
