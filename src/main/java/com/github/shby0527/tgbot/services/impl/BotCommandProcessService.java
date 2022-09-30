package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.properties.CommandRegisterProperties;
import com.github.shby0527.tgbot.services.EntityProcessService;
import com.github.shby0527.tgbot.services.RegisterBotCommandService;
import com.github.shby0527.tgbot.services.UnRegisterCommandExecutor;
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

    @Autowired
    private UnRegisterCommandExecutor unRegisterCommandExecutor;

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
        if (text.length() - offset == length) {
            arguments = new String[0];
        } else {
            String args = text.substring(offset + length + 1);
            arguments = args.split(" ");
        }
        String cmd = command.substring(1);
        CommandRegisterProperties.CommandMetadata commandMetadata = commandRegisterProperties.getCommands()
                .getOrDefault(cmd, null);
        if (commandMetadata == null) {
            // 这里进去的都是未注册命令
            unRegisterCommandExecutor.execute(cmd, arguments, origin);
            return;
        }
        RegisterBotCommandService commandService = registerBotCommandServiceMap.get(commandMetadata.getService());
        if (commandService == null) {
            // 这里进去的都是未注册命令
            unRegisterCommandExecutor.execute(cmd, arguments, origin);
            return;
        }
        commandService.process(arguments, origin);
    }

    @Override
    public String name() {
        return "botCommandProcessService";
    }
}
