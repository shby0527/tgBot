package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.dao.UserInfoMapper;
import com.github.shby0527.tgbot.entities.Userinfo;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.RegisterBotCommandService;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service("chatInfoCommandProcessor")
public class ChatInfoCommandProcessor implements RegisterBotCommandService {

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private IHttpService httpService;

    @Autowired
    private TelegramBotProperties botProperties;

    @Autowired
    private MessageSource messageSource;

    @Override
    public void process(String[] arguments, JsonNode node) {
        JsonNode from = JSONUtils.readJsonObject(node, "message.from", JsonNode.class);
        JsonNode chat = JSONUtils.readJsonObject(node, "message.chat", JsonNode.class);
        Long userId = from.get("id").longValue();
        Userinfo userinfo = userInfoMapper.selectByPrimaryKey(userId);
        if (userinfo == null) return;
        // >= 1 以上的权限可以使用，一般人是 0
        if (userinfo.getPermission() < 1) return;
        Locale locale = Locale.forLanguageTag(userinfo.getLanguageCode());
        String text = messageSource.getMessage("replay.chat-info.replay",
                new Object[]{
                        userinfo.getFirstname(), userinfo.getLastname(),
                        Optional.ofNullable(chat.get("title")).map(JsonNode::textValue).orElse("private chat"),
                        chat.get("type").textValue(),
                        Long.toString(chat.get("id").longValue())
                },
                "replay.chat-info.replay",
                locale);
        sendText(text, node);
    }


    private void sendText(String text, JsonNode origin) {
        Map<String, Object> post = new HashMap<>();
        JsonNode chat = JSONUtils.readJsonObject(origin, "message.chat", JsonNode.class);
        JsonNode from = JSONUtils.readJsonObject(origin, "message.from", JsonNode.class);
        Long messageId = JSONUtils.readJsonObject(origin, "message.message_id", Long.class);
        post.put("reply_to_message_id", messageId);
        post.put("text", text + "\n @" + Optional.ofNullable(from.get("username")).map(JsonNode::textValue).orElse(""));
        post.put("chat_id", chat.get("id").longValue());
        String url = botProperties.getUrl() + "sendMessage";
        try {
            String json = JSONUtils.OBJECT_MAPPER.writeValueAsString(post);
            log.debug("post data: {}", json);
            httpService.postForString(url, null, null, json, MediaType.APPLICATION_JSON_VALUE, httpResponse -> {
                try (httpResponse) {
                    JsonNode back = httpResponse.getJson();
                    log.debug("return back {}", back);
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            });
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }
}
