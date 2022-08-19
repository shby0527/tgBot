package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.dao.UserInfoMapper;
import com.github.shby0527.tgbot.entities.Userinfo;
import com.github.shby0527.tgbot.properties.CommandRegisterProperties;
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
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service("helpingCommandProcessor")
public class HelpingCommandProcessor implements RegisterBotCommandService {

    @Autowired
    private IHttpService httpService;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private TelegramBotProperties telegramBotProperties;

    @Autowired
    private CommandRegisterProperties commandRegisterProperties;

    @Autowired
    private MessageSource messageSource;

    @Override
    public void process(String[] arguments, JsonNode origin) {
        String type = JSONUtils.readJsonObject(origin, "message.chat.type", String.class);
        // 非私聊不处理
        if (!"private".equals(type)) return;
        JsonNode chat = JSONUtils.readJsonObject(origin, "message.chat", JsonNode.class);
        JsonNode from = JSONUtils.readJsonObject(origin, "message.from", JsonNode.class);
        Long messageId = JSONUtils.readJsonObject(origin, "message.message_id", Long.class);
        Long userId = from.get("id").longValue();
        Userinfo userinfo = userInfoMapper.selectByPrimaryKey(userId);
        String language = "ja";
        if (userinfo == null) {
            language = Optional.ofNullable(from.get("language_code")).map(JsonNode::textValue).orElse("ja");
        } else {
            language = userinfo.getLanguageCode();
        }
        Map<String, Object> post = new HashMap<>();
        post.put("reply_to_message_id", messageId);
        post.put("chat_id", chat.get("id").longValue());
        String atUser = Optional.ofNullable(from.get("username"))
                .map(JsonNode::textValue)
                .map(e -> "\n@" + e)
                .orElse("");
        Locale locale = Locale.forLanguageTag(language);
        String prefix = messageSource.getMessage("replay.help.prefix", null, "replay.help.prefix", locale);
        String suffix = messageSource.getMessage("replay.help.suffix", null, "replay.help.suffix", locale);
        StringJoiner joiner = new StringJoiner("\n", prefix + "\n", "\n" + suffix);
        commandRegisterProperties.getCommands().forEach((k, v) -> {
            StringBuilder sb = new StringBuilder("/" + k);
            sb.append(" ");
            sb.append(Arrays.stream(v.getArguments())
                    .map(arg -> {
                        String format;
                        if (arg.getOption()) {
                            format = "[%s]";
                        } else {
                            format = "<%s>";
                        }
                        return String.format(format, messageSource.getMessage(arg.getName(), null, arg.getName(), locale));
                    }).collect(Collectors.joining(" ")));
            sb.append(" ")
                    .append(v.getDescription());
            joiner.add(sb.toString());
        });
        post.put("text", joiner + atUser);
        String url = telegramBotProperties.getUrl() + "sendMessage";
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
