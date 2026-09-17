package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemConfigHistoryItemVo {

    private String historyId;
    private Integer beforeVersion;
    private Integer afterVersion;
    private String sourceType;
    private String sourceTypeLabel;
    private String changeNote;
    private String operatorName;
    private Date changedAt;
    private Integer changeCount;
    private List<SystemConfigChangeItemVo> changes;
    private String restoreFromHistoryId;
}
