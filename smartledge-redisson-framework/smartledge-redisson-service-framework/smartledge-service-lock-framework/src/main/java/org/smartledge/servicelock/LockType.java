package org.smartledge.servicelock;

/**
 * @description: 分布式锁 锁类型
 * @author: Song
 **/
public enum LockType {

    Reentrant,

    Fair,

    Read,

    Write;

    LockType() {
    }

}
