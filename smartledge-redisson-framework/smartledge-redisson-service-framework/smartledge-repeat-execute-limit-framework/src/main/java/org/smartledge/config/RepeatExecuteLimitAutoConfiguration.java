package org.smartledge.config;

import org.smartledge.constant.LockInfoType;
import org.smartledge.handle.RedissonDataHandle;
import org.smartledge.locallock.LocalLockCache;
import org.smartledge.lockinfo.LockInfoHandle;
import org.smartledge.lockinfo.factory.LockInfoHandleFactory;
import org.smartledge.lockinfo.impl.RepeatExecuteLimitLockInfoHandle;
import org.smartledge.repeatexecutelimit.aspect.RepeatExecuteLimitAspect;
import org.smartledge.servicelock.factory.ServiceLockFactory;
import org.springframework.context.annotation.Bean;

/**
 * @description: 防重复幂等配置
 * @author: Song
 **/
public class RepeatExecuteLimitAutoConfiguration {

    @Bean(LockInfoType.REPEAT_EXECUTE_LIMIT)
    public LockInfoHandle repeatExecuteLimitHandle(){
        return new RepeatExecuteLimitLockInfoHandle();
    }

    @Bean
    public RepeatExecuteLimitAspect repeatExecuteLimitAspect(LocalLockCache localLockCache,
                                                             LockInfoHandleFactory lockInfoHandleFactory,
                                                             ServiceLockFactory serviceLockFactory,
                                                             RedissonDataHandle redissonDataHandle){
        return new RepeatExecuteLimitAspect(localLockCache, lockInfoHandleFactory,serviceLockFactory,redissonDataHandle);
    }
}
