package org.smartledge.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @description: 视图对象
 * @author: Song
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeRouteTracePageVo {

    private String pageNo;

    private String pageSize;

    private String totalSize;

    private String totalPages;

    private List<KnowledgeRouteTraceItemVo> records;
}
