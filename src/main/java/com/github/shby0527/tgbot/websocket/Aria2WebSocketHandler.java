package com.github.shby0527.tgbot.websocket;

import com.github.shby0527.tgbot.properties.Aria2Properties;
import com.github.shby0527.tgbot.services.JsonRpcProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.io.IOException;

@Slf4j
@Service
public class Aria2WebSocketHandler implements WebSocketHandler {

    private static volatile WebSocketSession session;

    private static final Object SYNC_OBJ = new Object();

    @Autowired
    private JsonRpcProcessor jsonRpcProcessor;

    @Autowired
    private Aria2Properties aria2Properties;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.debug("websocket connect success");
        synchronized (SYNC_OBJ) {
            Aria2WebSocketHandler.session = session;
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        log.debug("get message: {}", message.getPayload());
        String text = (String) message.getPayload();
        jsonRpcProcessor.process(session, text);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("session error", exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        log.debug("closed,status: {}", closeStatus);
        synchronized (SYNC_OBJ) {
            Aria2WebSocketHandler.session = null;
        }
        // reconnect
        WebSocketClient client = new StandardWebSocketClient();
        client.doHandshake(this, null, aria2Properties.getAddress())
                .addCallback(new ListenableFutureCallback<WebSocketSession>() {
                    @Override
                    public void onFailure(Throwable ex) {
                        log.error("reconnecting fail", ex);
                    }

                    @Override
                    public void onSuccess(WebSocketSession result) {

                    }
                });
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    public static WebSocketSession getSession() throws IOException {
        synchronized (SYNC_OBJ) {
            if (session == null) {
                throw new IOException("session not standby");
            }
            return session;
        }
    }
}
