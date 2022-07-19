package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.properties.CommandRegisterProperties;
import com.github.shby0527.tgbot.services.EntityProcessService;
import com.github.shby0527.tgbot.services.RegisterBotCommandService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Service("botCommandProcessService")
public class BotCommandProcessService implements EntityProcessService {

    @Autowired
    private CommandRegisterProperties commandRegisterProperties;

    @Autowired(required = false)
    private Map<String, RegisterBotCommandService> registerBotCommandServiceMap = Collections.emptyMap();

    @Override
    public void process(JsonNode entity, JsonNode origin) {
        String text = JSONUtils.readJsonObject(origin, "message.text", String.class);
        int offset = entity.get("offset").intValue();
        int length = entity.get("length").intValue();
        String command = text.substring(offset, offset + length);
        // 判断一下有没有 @ 截取一下
        int indexOfAt = command.indexOf("@");
        if (indexOfAt >= 0) {
            command = command.substring(0, indexOfAt);
        }
        String[] arguments = null;
        if (text.length() == length) {
            arguments = new String[0];
        } else {
            String args = text.substring(offset + length + 1);
            arguments = args.split(" ");
        }
        String serviceName = commandRegisterProperties.getCommands()
                .getOrDefault(command.substring(1), "");
        RegisterBotCommandService commandService = registerBotCommandServiceMap.get(serviceName);
        if (commandService != null) {
            commandService.process(arguments, origin);
        }
    }

    @Override
    public String name() {
        return "botCommandProcessService";
    }
}
