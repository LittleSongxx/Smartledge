package org.smartledge.servicelock.info;

/**
 * @description: 分布式锁 策略
 * @author: Song
 **/
public enum LockTimeOutStrategy implements LockTimeOutHandler{

    FAIL(){
        @Override
        public void handler(String lockName) {
            String msg = String.format("%s请求频繁",lockName);
            throw new RuntimeException(msg);
        }
    }
}
