package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.properties.CommandRegisterProperties;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.RegisterBotCommandService;
import com.xw.task.services.HttpResponse;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private TelegramBotProperties telegramBotProperties;

    @Autowired
    private CommandRegisterProperties commandRegisterProperties;

    @Override
    public void process(String[] arguments, JsonNode origin) {
        JsonNode chat = JSONUtils.readJsonObject(origin, "message.chat", JsonNode.class);
        JsonNode from = JSONUtils.readJsonObject(origin, "message.from", JsonNode.class);
        Long messageId = JSONUtils.readJsonObject(origin, "message.message_id", Long.class);
        Map<String, Object> post = new HashMap<>();
        post.put("reply_to_message_id", messageId);
        post.put("chat_id", chat.get("id").longValue());
        String atUser = Optional.ofNullable(from.get("username"))
                .map(JsonNode::textValue)
                .map(e -> "\n@" + e)
                .orElse("");
        StringJoiner joiner = new StringJoiner("\n", "あたしキツネビは、これしか分からないぞ\n", "\n有り難う御座います。");
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
                        return String.format(format, arg.getName());
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
