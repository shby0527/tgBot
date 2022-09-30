package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.dao.UserInfoMapper;
import com.github.shby0527.tgbot.entities.Userinfo;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.OssResourceLoader;
import com.github.shby0527.tgbot.services.UnRegisterCommandExecutor;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import com.xw.web.utils.StringReplaceUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class UnRegisterCommandExecutorImpl implements UnRegisterCommandExecutor {


    @Autowired
    private IHttpService httpService;

    @Autowired
    private TelegramBotProperties botProperties;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private OssResourceLoader resourceLoader;


    @Override
    public void execute(String command, String[] arguments, JsonNode origin) {
        JsonNode message = origin.get("message");
        JsonNode from = message.get("from");
        Locale local = getUserLocal(from);
        JsonNode jsonNode = resourceLoader.readForUnRegisterAction();
        // 读取语言特性
        JsonNode language = jsonNode.get(local.getLanguage());
        if (language == null) {
            language = jsonNode.get("default");
        }
        // 从语言特性中读取cmd
        if (!language.has(command)) {
            return;
        }
        if (!message.has("reply_to_message")) {
            return;
        }
        JsonNode commandReplay = language.get(command);
        String template = commandReplay.textValue();
        // 准备内容
        JsonNode chat = message.get("chat");
        Long messageId = message.get("message_id").longValue();
        JsonNode replayMessage = message.get("reply_to_message");
        JsonNode replayUser = replayMessage.get("from");
        Map<String, String> mp = new HashMap<>();
        mp.put("fromUsername", Optional.ofNullable(from.get("username")).map(JsonNode::textValue).orElse(""));
        mp.put("fromFirstName", Optional.ofNullable(from.get("first_name")).map(JsonNode::textValue).orElse(""));
        mp.put("fromLastName", Optional.ofNullable(from.get("last_name")).map(JsonNode::textValue).orElse(""));
        mp.put("replayUsername", Optional.ofNullable(replayUser.get("username")).map(JsonNode::textValue).orElse(""));
        mp.put("replayFirstName", Optional.ofNullable(replayUser.get("first_name")).map(JsonNode::textValue).orElse(""));
        mp.put("replayLastName", Optional.ofNullable(replayUser.get("last_name")).map(JsonNode::textValue).orElse(""));
        mp.put("chatTitle", Optional.ofNullable(chat.get("title")).map(JsonNode::textValue).orElse(""));
        mp.put("chatUsername", Optional.ofNullable(chat.get("username")).map(JsonNode::textValue).orElse(""));

        String finalStr = StringReplaceUtils.replaceWithMap(template, mp);

        sendText(finalStr, origin);
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

    private Locale getUserLocal(JsonNode from) {
        Long userId = from.get("id").longValue();
        Userinfo userinfo = userInfoMapper.selectByPrimaryKey(userId);
        String language = "ja";
        if (userinfo == null) {
            language = Optional.ofNullable(from.get("language_code")).map(JsonNode::textValue).orElse("ja");
        } else {
            language = userinfo.getLanguageCode();
        }
        return Locale.forLanguageTag(language);
    }
}
