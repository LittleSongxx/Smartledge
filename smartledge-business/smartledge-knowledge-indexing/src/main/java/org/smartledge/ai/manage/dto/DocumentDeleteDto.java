package org.smartledge.ai.manage.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @description: 数据传输对象
 * @author: Song
 **/

@Data
public class DocumentDeleteDto {

    @NotBlank(message = "文档id不能为空")
    private String documentId;
}
