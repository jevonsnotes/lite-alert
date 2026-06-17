package io.litealert.notify;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async pool used by NotifyDispatcher. Bounded queue + caller-runs prevents
 * runaway memory if SMTP backs up.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "notifyExecutor")
    public Executor notifyExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(1000);
        ex.setThreadNamePrefix("lite-alert-notify-");
        ex.setKeepAliveSeconds(60);
        ex.initialize();
        return ex;
    }
}
