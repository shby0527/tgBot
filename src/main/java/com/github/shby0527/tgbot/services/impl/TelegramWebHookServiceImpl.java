package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.WebHookService;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@ConfigurationProperties(prefix = "bot.webhook.telegram")
public class TelegramWebHookServiceImpl implements WebHookService {

    @Autowired
    private IHttpService httpService;

    @Autowired
    private TelegramBotProperties botProperties;

    private Boolean enabled = false;

    private Map<String, Long> targetMap = Collections.emptyMap();

    public Map<String, Long> getTargetMap() {
        return targetMap;
    }

    public void setTargetMap(Map<String, Long> targetMap) {
        this.targetMap = targetMap;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String handler(String body, String target) {
        if (!this.enabled) return "SKIP";
        Long userId = targetMap.get(target);
        if (userId == null) return "NO TARGET";
        Map<String, Object> post = new HashMap<>();
        post.put("text", body);
        post.put("chat_id", userId);
        String url = botProperties.getUrl() + "sendMessage";
        try {
            String json = JSONUtils.OBJECT_MAPPER.writeValueAsString(post);
            log.debug("post data: {}", json);
            httpService.postForString(url, null, null, json, MediaType.APPLICATION_JSON_VALUE, httpResponse -> {
                try (httpResponse) {
                    JsonNode back = httpResponse.getJson();
                    log.debug("return back {}", back);
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            });
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return "OK";
    }
}
