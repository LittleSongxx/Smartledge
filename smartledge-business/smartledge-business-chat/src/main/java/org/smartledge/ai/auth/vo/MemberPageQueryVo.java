package org.smartledge.ai.auth.vo;

import java.util.List;

/**
 * 成员分页（S23-B2）。
 */
public class TenantMemberPageQueryVo {

    private final Integer pageNo;

    private final Integer pageSize;

    private final Long total;

    private final List<TenantMemberItemVo> records;

    public TenantMemberPageQueryVo(Integer pageNo, Integer pageSize, Long total, List<TenantMemberItemVo> records) {
        this.pageNo = pageNo;
        this.pageSize = pageSize;
        this.total = total;
        this.records = records;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public Long getTotal() {
        return total;
    }

    public List<TenantMemberItemVo> getRecords() {
        return records;
    }
}
