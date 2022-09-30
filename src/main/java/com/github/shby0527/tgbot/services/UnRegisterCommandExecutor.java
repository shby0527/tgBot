package com.github.shby0527.tgbot.services;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 为注册指令的默认处理
 */
public interface UnRegisterCommandExecutor {

    void execute(String command, String[] arguments, JsonNode origin);
}
