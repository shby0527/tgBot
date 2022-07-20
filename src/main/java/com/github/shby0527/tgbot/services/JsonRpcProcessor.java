package com.github.shby0527.tgbot.services;

import org.springframework.web.socket.WebSocketSession;

public interface JsonRpcProcessor {

    void process(WebSocketSession session, String json);
}
