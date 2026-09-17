package org.smartledge.servicelock.factory;

import org.smartledge.core.ManageLocker;
import org.smartledge.servicelock.LockType;
import org.smartledge.servicelock.ServiceLocker;
import lombok.AllArgsConstructor;

/**
 * @description: 分布式锁类型工厂
 * @author: Song
 **/
@AllArgsConstructor
public class ServiceLockFactory {

    private final ManageLocker manageLocker;

    public ServiceLocker getLock(LockType lockType){
        ServiceLocker lock;
        switch (lockType) {
            case Fair:
                lock = manageLocker.getFairLocker();
                break;
            case Write:
                lock = manageLocker.getWriteLocker();
                break;
            case Read:
                lock = manageLocker.getReadLocker();
                break;
            default:
                lock = manageLocker.getReentrantLocker();
                break;
        }
        return lock;
    }
}
