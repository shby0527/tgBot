package com.github.shby0527.tgbot.services.impl;

import com.github.shby0527.tgbot.properties.Aria2Properties;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

@Slf4j
@Service
public class WebSocketStarter implements ApplicationRunner {

    @Autowired
    private WebSocketHandler webSocketHandler;

    @Autowired
    private Aria2Properties aria2Properties;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(1024000);
        container.setDefaultMaxBinaryMessageBufferSize(1024000);
        StandardWebSocketClient webSocketClient = new StandardWebSocketClient(container);
        webSocketClient.execute(webSocketHandler, null, aria2Properties.getAddress());
    }
}
