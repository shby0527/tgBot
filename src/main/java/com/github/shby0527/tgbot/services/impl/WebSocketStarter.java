package com.github.shby0527.tgbot.services.impl;

import com.github.shby0527.tgbot.properties.Aria2Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

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
        webSocketClient.doHandshake(webSocketHandler, null, aria2Properties.getAddress())
                .addCallback(new ListenableFutureCallback<>() {
                    @Override
                    public void onFailure(Throwable ex) {
                        log.error("connect fail", ex);
                    }

                    @Override
                    public void onSuccess(WebSocketSession result) {

                    }
                });
    }
}
