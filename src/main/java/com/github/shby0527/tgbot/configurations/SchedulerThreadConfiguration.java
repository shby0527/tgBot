package com.github.shby0527.tgbot.configurations;

import com.xw.task.properties.ThreadPoolProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class SchedulerThreadConfiguration {


    @Bean("taskScheduler")
    public TaskScheduler taskScheduler(ThreadPoolProperties properties) {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setThreadNamePrefix(properties.getThreadNamePrefix() + "scheduler-");
        taskScheduler.setDaemon(true);
        taskScheduler.setPoolSize(properties.getMaxSize());
        taskScheduler.setBeanName("taskScheduler");
        taskScheduler.initialize();
        return taskScheduler;
    }
}
