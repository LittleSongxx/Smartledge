package org.smartledge.core;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import static org.smartledge.constant.Constant.DEFAULT_PREFIX_DISTINCTION_NAME;
import static org.smartledge.constant.Constant.PREFIX_DISTINCTION_NAME;

/**
 * @description: 工具类
 * @author: Song
 **/

public class SpringUtil implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static ConfigurableApplicationContext configurableApplicationContext;

    public static String getPrefixDistinctionName(){
        return configurableApplicationContext.getEnvironment().getProperty(PREFIX_DISTINCTION_NAME,
                DEFAULT_PREFIX_DISTINCTION_NAME);
    }

    @Override
    public void initialize(final ConfigurableApplicationContext applicationContext) {
        configurableApplicationContext = applicationContext;
    }

    public static <T> T getBean(Class<T> requiredType){
        return configurableApplicationContext.getBean(requiredType);
    }

    public static <T> T getBean(String name, Class<T> requiredType){
        return configurableApplicationContext.getBean(name,requiredType);
    }
}
