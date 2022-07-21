package com.github.shby0527.tgbot.services;

import com.fasterxml.jackson.databind.JsonNode;

public interface InlineCallbackService {

    void process(String[] arguments, JsonNode origin);
}
