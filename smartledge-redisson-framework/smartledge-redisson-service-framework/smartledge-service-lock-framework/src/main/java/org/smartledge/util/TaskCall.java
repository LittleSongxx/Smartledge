package org.smartledge.util;

/**
 * @description: 分布式锁 方法类型执行 有返回值的业务
 * @author: Song
 **/
@FunctionalInterface
public interface TaskCall<V> {

    V call();
}
