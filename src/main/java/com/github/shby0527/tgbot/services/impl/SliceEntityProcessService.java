package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.services.EntityProcessService;
import org.springframework.stereotype.Service;

@Service("sliceEntityProcessService")
public class SliceEntityProcessService implements EntityProcessService {
    @Override
    public void process(JsonNode entity, JsonNode origin) {

    }

    @Override
    public String name() {
        return "sliceProcess";
    }
}
