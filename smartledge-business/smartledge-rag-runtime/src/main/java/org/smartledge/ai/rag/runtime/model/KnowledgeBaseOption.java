package org.smartledge.ai.rag.runtime.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseOption {

    private String id;
    private String baseName;
    private String description;
    private String isDefault;
    private String retrievableDocumentCount;
}
