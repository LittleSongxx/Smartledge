package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.dto.QualityOverviewQueryDto;
import org.smartledge.ai.manage.vo.QualityOverviewVo;

/**
 * @description: 服务层
 * @author: Song
 **/
public interface QualityOverviewService {

    /**
     * 查询知识运行全景：路由走势、通道剖面、裁决结构与文档处理耗时。
     */
    QualityOverviewVo queryQualityOverview(QualityOverviewQueryDto dto);
}
