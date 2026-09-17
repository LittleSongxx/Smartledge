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
public class DocumentPageQueryVo {

    private Integer pageNo;

    private Integer pageSize;

    private Long total;

    private List<DocumentListItemVo> records;
}
