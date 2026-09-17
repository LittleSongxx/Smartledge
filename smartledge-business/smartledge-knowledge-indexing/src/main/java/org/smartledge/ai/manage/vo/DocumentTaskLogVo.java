package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * @description: 视图对象
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTaskLogVo {

    private Long id;

    private Integer stageType;

    private String stageTypeName;

    private Integer eventType;

    private String eventTypeName;

    private Integer logLevel;

    private String logLevelName;

    private String content;

    private String detailJson;

    private Date createTime;
}
