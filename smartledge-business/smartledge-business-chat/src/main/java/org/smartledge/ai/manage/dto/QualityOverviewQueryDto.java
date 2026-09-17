package org.smartledge.ai.manage.dto;

import lombok.Data;

/**
 * @description: 数据传输对象
 * @author: Song
 **/
@Data
public class QualityOverviewQueryDto {

    /**
     * 统计时间窗天数。空或非法值按默认 30 天处理，"0" 表示全部时间。
     */
    private String windowDays;
}
