package org.smartledge.util;

/**
 * @description: 分布式锁 方法类型执行 无返回值的业务
 * @author: Song
 **/
@FunctionalInterface
public interface TaskRun {

    void run();
}
