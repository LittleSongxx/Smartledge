package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.dto.SystemConfigHistoryDetailQueryDto;
import org.smartledge.ai.manage.dto.SystemConfigHistoryPageQueryDto;
import org.smartledge.ai.manage.dto.SystemConfigHistoryRestoreDto;
import org.smartledge.ai.manage.dto.SystemConfigItemUpdateDto;
import org.smartledge.ai.manage.vo.SystemConfigCurrentVo;
import org.smartledge.ai.manage.vo.SystemConfigHistoryItemVo;
import org.smartledge.ai.manage.vo.SystemConfigHistoryPageVo;

public interface SystemConfigService {

    SystemConfigCurrentVo current();

    SystemConfigCurrentVo updateItem(SystemConfigItemUpdateDto dto, String operatorName);

    SystemConfigHistoryPageVo queryHistory(SystemConfigHistoryPageQueryDto dto);

    SystemConfigHistoryItemVo queryHistoryDetail(SystemConfigHistoryDetailQueryDto dto);

    SystemConfigCurrentVo restore(SystemConfigHistoryRestoreDto dto, String operatorName);
}
