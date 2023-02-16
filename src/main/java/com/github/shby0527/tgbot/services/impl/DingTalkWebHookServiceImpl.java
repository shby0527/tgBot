package com.github.shby0527.tgbot.services.impl;

import com.github.shby0527.tgbot.services.WebHookService;
import com.xw.task.services.IMessageSender;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Service
@ConfigurationProperties(prefix = "bot.webhook.ding-talk")
public class DingTalkWebHookServiceImpl implements WebHookService {

    private Boolean enabled = false;

    private Map<String, String> targetMap = Collections.emptyMap();

    private String title = "通知";

    @Autowired
    private BeanFactory beanFactory;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getTargetMap() {
        return targetMap;
    }

    public void setTargetMap(Map<String, String> targetMap) {
        this.targetMap = targetMap;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    @Override
    public String handler(String body, String target) {
        if (!this.enabled) return "SKIP";
        String t = targetMap.get(target);
        if (StringUtils.isEmpty(t)) return "NO TARGET";
        IMessageSender messageSender = beanFactory.getBean("dingRobotMessageSender", IMessageSender.class);
        messageSender.sendMessage(title, body, Collections.singleton(t));
        return "OK";
    }
}
