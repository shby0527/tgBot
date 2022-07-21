package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.EntityProcessService;
import com.github.shby0527.tgbot.services.InlineCallbackService;
import com.github.shby0527.tgbot.services.TelegramBotProcessService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class TelegramBotProcessServiceImpl implements TelegramBotProcessService {

    @Autowired
    private TelegramBotProperties telegramBotProperties;

    @Autowired
    private Map<String, EntityProcessService> entityProcessServiceMap;

    @Autowired
    private Map<String, InlineCallbackService> inlineCallbackServiceMap;

    private static final Pattern INLINE_CALLBACK_PATTERN = Pattern.compile("^(\\w+)=(.*)$");

    @Override
    @Async("statsExecutor")
    public void process(JsonNode jsonNode) {
        log.debug("received message: {}", jsonNode);
        JsonNode message = jsonNode.get("message");
        if (message != null) {
            JsonNode entities = message.get("entities");
            if (entities == null) return;
            log.debug("read all entities: {}", entities);
            for (JsonNode item : entities) {
                String type = JSONUtils.readJsonObject(item, "type", String.class);
                String processor = telegramBotProperties.getServiceToProcess().getOrDefault(type, telegramBotProperties.getDefaultProcess());
                EntityProcessService entityProcessService = entityProcessServiceMap.get(processor);
                log.debug("dispatched type {} to the {}", type, entityProcessService.name());
                entityProcessService.process(item, jsonNode);
            }
            return;
        }
        JsonNode callback = jsonNode.get("callback_query");
        if (callback != null) {
            String data = callback.get("data").textValue();
            Matcher matcher = INLINE_CALLBACK_PATTERN.matcher(data);
            if (!matcher.find()) {
                return;
            }
            String service = matcher.group(1);
            String[] arguments = matcher.group(2).split(",");
            InlineCallbackService inlineCallbackService = inlineCallbackServiceMap.get(service);
            if (inlineCallbackService == null) return;
            inlineCallbackService.process(arguments, jsonNode);
        }
    }
}
