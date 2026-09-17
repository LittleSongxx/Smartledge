package com.baidu.fsg.uid.buffer;

import java.util.List;

/**
 * @description: /
 * @author: Song
 **/

@FunctionalInterface
public interface BufferedUidProvider {

    List<Long> provide(long momentInSecond);
}
