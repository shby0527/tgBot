package com.github.shby0527.tgbot.services;

import com.fasterxml.jackson.databind.JsonNode;

public interface EntityProcessService {

    void process(JsonNode entity, JsonNode origin);


    String name();
}
