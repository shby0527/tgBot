package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.dao.UserInfoMapper;
import com.github.shby0527.tgbot.entities.Userinfo;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.RegisterBotCommandService;
import com.xw.task.services.HttpResponse;
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
@Service("userInfoCommandProcessor")
public class UserInfoCommandProcessor implements RegisterBotCommandService {

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
        String type = JSONUtils.readJsonObject(node, "message.chat.type", String.class);
        // 非私聊不处理
        if (!"private".equals(type)) return;
        Long id = JSONUtils.readJsonObject(node, "message.from.id", Long.class);
        JsonNode from = JSONUtils.readJsonObject(node, "message.from", JsonNode.class);
        Userinfo userinfo = userInfoMapper.selectByPrimaryKey(id);
        if (userinfo == null) {
            userinfo = new Userinfo();
            userinfo.setId(id);
            userinfo.setFirstname(Optional.ofNullable(from.get("first_name")).map(JsonNode::textValue).orElse(""));
            userinfo.setLastname(Optional.ofNullable(from.get("last_name")).map(JsonNode::textValue).orElse(""));
            userinfo.setLanguageCode(Optional.ofNullable(from.get("language_code")).map(JsonNode::textValue).orElse("ja-jp"));
            userinfo.setPermission(0);
            userInfoMapper.insertSelective(userinfo);
        }
        Locale locale = Locale.forLanguageTag(userinfo.getLanguageCode());
        String send = messageSource.getMessage("replay.my-info.replay", new Object[]{
                userinfo.getFirstname(), userinfo.getLastname(), id.toString(), userinfo.getLanguageCode()
        }, "replay.my-info.replay", locale);
        sendText(send, node);
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
            try (HttpResponse response = httpService.postForString(url, null, null, json, MediaType.APPLICATION_JSON_VALUE, null)) {
                JsonNode back = response.getJson();
                log.debug("return back {}", back);
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }
}
