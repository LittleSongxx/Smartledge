package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemConfigHistoryPageVo {
    private Integer pageNo;
    private Integer pageSize;
    private Long total;
    private Long totalPages;
    private List<SystemConfigHistoryItemVo> records;
}
