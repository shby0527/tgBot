package com.github.shby0527.tgbot.services;

/**
 * 调度服务
 */
public interface SchedulerService {

    /**
     * 执行调度
     */
    void scheduler(Long chatId, String arguments);
}
