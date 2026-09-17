package org.smartledge.core;

import org.smartledge.servicelock.LockType;
import org.smartledge.servicelock.ServiceLocker;
import org.smartledge.servicelock.impl.RedissonFairLocker;
import org.smartledge.servicelock.impl.RedissonReadLocker;
import org.smartledge.servicelock.impl.RedissonReentrantLocker;
import org.smartledge.servicelock.impl.RedissonWriteLocker;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;

import static org.smartledge.servicelock.LockType.Fair;
import static org.smartledge.servicelock.LockType.Read;
import static org.smartledge.servicelock.LockType.Reentrant;
import static org.smartledge.servicelock.LockType.Write;

/**
 * @description: 分布式锁 锁缓存
 * @author: Song
 **/
public class ManageLocker {

    private final Map<LockType, ServiceLocker> cacheLocker = new HashMap<>();

    public ManageLocker(RedissonClient redissonClient){
        cacheLocker.put(Reentrant,new RedissonReentrantLocker(redissonClient));
        cacheLocker.put(Fair,new RedissonFairLocker(redissonClient));
        cacheLocker.put(Write,new RedissonWriteLocker(redissonClient));
        cacheLocker.put(Read,new RedissonReadLocker(redissonClient));
    }

    public ServiceLocker getReentrantLocker(){
        return cacheLocker.get(Reentrant);
    }

    public ServiceLocker getFairLocker(){
        return cacheLocker.get(Fair);
    }

    public ServiceLocker getWriteLocker(){
        return cacheLocker.get(Write);
    }

    public ServiceLocker getReadLocker(){
        return cacheLocker.get(Read);
    }
}
