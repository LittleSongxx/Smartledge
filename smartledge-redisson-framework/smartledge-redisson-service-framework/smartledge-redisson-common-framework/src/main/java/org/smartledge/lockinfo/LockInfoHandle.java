package org.smartledge.lockinfo;

import org.aspectj.lang.JoinPoint;

/**
 * @description: 锁信息抽象
 * @author: Song
 **/
public interface LockInfoHandle {

    String getLockName(JoinPoint joinPoint, String name, String[] keys);

    String simpleGetLockName(String name,String[] keys);
}
