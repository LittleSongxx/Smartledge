package org.smartledge.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @description: 视图对象
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentOptionView {

    private String documentId;
    private String documentName;
    private String knowledgeBaseId;
    private String knowledgeBaseName;
}
