package org.smartledge.servicelock.info;

/**
 * @description: 分布式锁 处理失败抽象
 * @author: Song
 **/
public interface LockTimeOutHandler {

    void handler(String lockName);
}
