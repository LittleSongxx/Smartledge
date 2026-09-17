package org.smartledge.lockinfo.impl;

import org.smartledge.lockinfo.AbstractLockInfoHandle;

/**
 * @description: 锁信息实现(分布式锁)
 * @author: Song
 **/
public class ServiceLockInfoHandle extends AbstractLockInfoHandle {

    private static final String LOCK_PREFIX_NAME = "SERVICE_LOCK";

    @Override
    protected String getLockPrefixName() {
        return LOCK_PREFIX_NAME;
    }
}
