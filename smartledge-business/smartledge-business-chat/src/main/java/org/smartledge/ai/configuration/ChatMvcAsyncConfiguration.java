package org.smartledge.ai.configuration;

import org.smartledge.ai.manage.service.SystemConfigProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.RejectedExecutionException;

/** Owns Servlet response writing only; model and RAG execution retain their existing executors. */
@Configuration(proxyBeanMethods = false)
public class ChatMvcAsyncConfiguration {

    @Bean
    public ThreadPoolTaskExecutor chatMvcAsyncExecutor(SystemConfigProvider provider) {
        var options = provider.currentSnapshot().getMvcAsync();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-mvc-async-");
        executor.setCorePoolSize(options.getCorePoolSize());
        executor.setMaxPoolSize(options.getMaxPoolSize());
        executor.setQueueCapacity(options.getQueueCapacity());
        executor.setKeepAliveSeconds(options.getKeepAliveSeconds());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(options.getShutdownAwaitSeconds());
        executor.setRejectedExecutionHandler((task, pool) -> {
            if (pool.isShutdown()) {
                throw new RejectedExecutionException("MVC response executor is shut down");
            }
            // MVC's reactive adapter cancels upstream when submission throws. Under load,
            // run the write in the submitting thread instead of losing a response task.
            // maxPoolSize bounds pool workers, not all callers participating in writes.
            task.run();
        });
        return executor;
    }

    @Bean
    public WebMvcConfigurer chatMvcAsyncConfigurer(
            @Qualifier("chatMvcAsyncExecutor") ThreadPoolTaskExecutor executor) {
        return new WebMvcConfigurer() {
            @Override
            public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setTaskExecutor(executor);
            }
        };
    }
}
