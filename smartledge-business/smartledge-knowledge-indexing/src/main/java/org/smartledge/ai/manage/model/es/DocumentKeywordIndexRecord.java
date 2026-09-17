package org.smartledge.ai.manage.model.es;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: Elasticsearch 关键词索引文档
 * @author: Song
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentKeywordIndexRecord {

    private String chunkId;

    private Long documentId;

    private Long taskId;

    private Long parentBlockId;

    private Integer chunkNo;

    private String documentName;

    private Long knowledgeBaseId;

    private String knowledgeBaseName;

    private String sectionPath;

    private Long structureNodeId;

    private Integer structureNodeType;

    private String canonicalPath;

    private Integer itemIndex;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private String sourceBlockIds;

    private String contentWithWeight;

    private String chunkType;

    private String title;

    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    @Builder.Default
    private List<String> questions = new ArrayList<>();

    private String chunkText;
}
