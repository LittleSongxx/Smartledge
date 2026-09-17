package org.smartledge.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @description: 启动类
 * @author: Song
 **/
@SpringBootApplication
@EnableScheduling
public class SmartledgeBusinessChatAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartledgeBusinessChatAgentApplication.class, args);
    }

}
