package org.smartledge.database.page;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
/**
 * @description: 视图对象
 * @author: Song
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageVo<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private long pageNum;

    private long pageSize;

    private long totalSize;

    private List<T> list;
}
