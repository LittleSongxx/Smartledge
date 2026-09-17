package org.smartledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.concurrent.TimeUnit;

import static org.smartledge.config.DelayQueueProperties.PREFIX;

/**
 * @description: 延迟队列 配置属性
 * @author: Song
 **/
@Data
@ConfigurationProperties(prefix = PREFIX)
public class DelayQueueProperties {

    public static final String PREFIX = "delay.queue";

    private Integer corePoolSize = 4;

    private Integer maximumPoolSize = 4;

    private long keepAliveTime = 30;

    private TimeUnit unit = TimeUnit.SECONDS;

    private Integer workQueueSize = 256;

    private Integer isolationRegionCount = 5;
}
