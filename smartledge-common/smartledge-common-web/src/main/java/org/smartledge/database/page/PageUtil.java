package org.smartledge.database.page;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.pagehelper.PageInfo;
import org.smartledge.database.dto.BasePageDto;

import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @description: 工具类
 * @author: Song
 **/

public class PageUtil {

    public static <T> IPage<T> getPageParams(BasePageDto basePageDto) {
        return getPageParams(basePageDto.getPageNumber(), basePageDto.getPageSize());
    }

    public static <T> IPage<T> getPageParams(int pageNumber, int pageSize) {
        return new Page<>(pageNumber, pageSize);
    }

    public static <OLD,NEW> PageVo<NEW> convertPage(PageInfo<OLD> pageInfo, Function<? super OLD, ? extends NEW> function){
        return new PageVo<>(pageInfo.getPageNum(),
                pageInfo.getPageSize(),
                pageInfo.getTotal(),
                pageInfo.getList().stream().map(function).collect(Collectors.toList()));
    }

    public static <OLD,NEW> PageVo<NEW> convertPage(IPage<OLD> iPage, Function<? super OLD, ? extends NEW> function){
        return new PageVo<>(iPage.getCurrent(),
                iPage.getSize(),
                iPage.getTotal(),
                iPage.getRecords().stream().map(function).collect(Collectors.toList()));
    }
}
