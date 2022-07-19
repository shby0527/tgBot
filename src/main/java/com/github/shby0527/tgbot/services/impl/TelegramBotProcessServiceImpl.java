package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.EntityProcessService;
import com.github.shby0527.tgbot.services.TelegramBotProcessService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;

@Slf4j
@Service
public class TelegramBotProcessServiceImpl implements TelegramBotProcessService {

    @Autowired
    private TelegramBotProperties telegramBotProperties;

    @Autowired
    private Map<String, EntityProcessService> entityProcessServiceMap;


    @Override
    @Async("statsExecutor")
    public String process(JsonNode jsonNode) {
        log.debug("received message: {}", jsonNode);
        Collection<JsonNode> entities = JSONUtils.readJsonForList(jsonNode, "message.entities", JsonNode.class);
        log.debug("read all entities: {}", entities);
        for (JsonNode item : entities) {
            String type = JSONUtils.readJsonObject(item, "type", String.class);
            String processor = telegramBotProperties.getServiceToProcess().getOrDefault(type, telegramBotProperties.getDefaultProcess());
            EntityProcessService entityProcessService = entityProcessServiceMap.get(processor);
            log.debug("dispatched type {} to the {}", type, entityProcessService.name());
            entityProcessService.process(item, jsonNode);
        }
        return "True";
    }
}
