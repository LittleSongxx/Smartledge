package org.smartledge.ai.manage.dto;

import lombok.Data;

import java.util.List;

/**
 * @description: 数据传输对象
 * @author: Song
 **/
@Data
public class DocumentProfileBatchRegenerateDto {

    private List<String> documentIds;

    private String operatorId;
}
