package org.smartledge.ai.manage.dto;

import lombok.Data;

/**
 * @description: 数据传输对象
 * @author: Song
 **/

@Data
public class DocumentPageQueryDto {

    private Integer pageNo;

    private Integer pageSize;

    private String keyword;
}
