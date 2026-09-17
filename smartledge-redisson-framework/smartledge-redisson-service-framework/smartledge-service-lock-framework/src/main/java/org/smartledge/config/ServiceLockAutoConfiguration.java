package org.smartledge.config;

import org.smartledge.constant.LockInfoType;
import org.smartledge.core.ManageLocker;
import org.smartledge.lockinfo.LockInfoHandle;
import org.smartledge.lockinfo.factory.LockInfoHandleFactory;
import org.smartledge.lockinfo.impl.ServiceLockInfoHandle;
import org.smartledge.servicelock.aspect.ServiceLockAspect;
import org.smartledge.servicelock.factory.ServiceLockFactory;
import org.smartledge.util.ServiceLockTool;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;

/**
 * @description: 分布式锁 配置
 * @author: Song
 **/
public class ServiceLockAutoConfiguration {

    @Bean(LockInfoType.SERVICE_LOCK)
    public LockInfoHandle serviceLockInfoHandle(){
        return new ServiceLockInfoHandle();
    }

    @Bean
    public ManageLocker manageLocker(RedissonClient redissonClient){
        return new ManageLocker(redissonClient);
    }

    @Bean
    public ServiceLockFactory serviceLockFactory(ManageLocker manageLocker){
        return new ServiceLockFactory(manageLocker);
    }

    @Bean
    public ServiceLockAspect serviceLockAspect(LockInfoHandleFactory lockInfoHandleFactory,ServiceLockFactory serviceLockFactory){
        return new ServiceLockAspect(lockInfoHandleFactory,serviceLockFactory);
    }

    @Bean
    public ServiceLockTool serviceLockUtil(LockInfoHandleFactory lockInfoHandleFactory,ServiceLockFactory serviceLockFactory){
        return new ServiceLockTool(lockInfoHandleFactory,serviceLockFactory);
    }
}
