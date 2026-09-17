package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * @description: RAG 学习快照查询参数
 * @author: Song
 **/

@Data
public class DocumentRagSnapshotQueryDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;

    private Long parseTaskId;

    private Long indexTaskId;

    private Long highlightTableId;

    private Integer highlightTableNo;

    private List<Integer> highlightRowNos;

    private List<String> highlightColumnNames;

    private List<String> highlightCellCoordinates;
}
