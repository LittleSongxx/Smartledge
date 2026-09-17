package org.smartledge.ai.rag.runtime.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @description: 可参与知识检索的文档描述对象
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentDescriptor {

    private Long documentId;

    private String documentName;

    private Long lastIndexTaskId;

    private Long knowledgeBaseId;

    private String knowledgeBaseName;
}
