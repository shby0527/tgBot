package com.github.shby0527.tgbot.services;

import com.fasterxml.jackson.databind.JsonNode;

public interface TelegramBotProcessService {

    void process(JsonNode jsonNode);
}
