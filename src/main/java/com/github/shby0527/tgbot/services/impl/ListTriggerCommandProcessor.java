package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.dao.UserInfoMapper;
import com.github.shby0527.tgbot.dao.UserJobsMapper;
import com.github.shby0527.tgbot.entities.Userinfo;
import com.github.shby0527.tgbot.entities.Userjobs;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.RegisterBotCommandService;
import com.xw.task.services.HttpResponse;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service("listTriggerCommandProcessor")
public class ListTriggerCommandProcessor implements RegisterBotCommandService {

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private UserJobsMapper userJobsMapper;

    @Autowired
    private IHttpService httpService;

    @Autowired
    private TelegramBotProperties botProperties;

    @Override
    public void process(String[] arguments, JsonNode node) {
        JsonNode chat = JSONUtils.readJsonObject(node, "message.chat", JsonNode.class);
        JsonNode from = JSONUtils.readJsonObject(node, "message.from", JsonNode.class);
        Long userId = from.get("id").longValue();
        Userinfo userinfo = userInfoMapper.selectByPrimaryKey(userId);
        if (userinfo == null) return;
        String type = chat.get("type").textValue();
        if (!"private".equals(type)) {
            // 只要不是私聊频道，权限过低的都不允许创建
            if (userinfo.getPermission() < 2) return;
        }
        Long chatId = chat.get("id").longValue();
        List<Userjobs> myChatJobs = userJobsMapper.getMyChatJobs(userId, chatId);
        if (myChatJobs.isEmpty()) {
            sendText("ご主人さまは、この場所で何も計画が見えません", node, null);
            return;
        }
        sendText("ご主人さまのご計画はこちらです", node, myChatJobs);
    }

    private void sendText(String text, JsonNode origin, List<Userjobs> chatJobs) {
        Map<String, Object> post = new HashMap<>();
        JsonNode chat = JSONUtils.readJsonObject(origin, "message.chat", JsonNode.class);
        JsonNode from = JSONUtils.readJsonObject(origin, "message.from", JsonNode.class);
        Long messageId = JSONUtils.readJsonObject(origin, "message.message_id", Long.class);
        post.put("reply_to_message_id", messageId);
        post.put("text", text + "\n @" + Optional.ofNullable(from.get("username")).map(JsonNode::textValue).orElse(""));
        post.put("chat_id", chat.get("id").longValue());
        if (chatJobs != null && !chatJobs.isEmpty()) {
            Map<String, Object> reply_markup = new HashMap<>(1);
            post.put("reply_markup", reply_markup);
            AtomicInteger integer = new AtomicInteger(0);
            Collection<List<Map<String, String>>> root = chatJobs.stream()
                    .collect(Collectors.groupingBy(ignored -> integer.getAndIncrement() % 3,
                            Collectors.mapping(v -> {
                                Map<String, String> selection = new HashMap<>();
                                selection.put("text", v.getName());
                                selection.put("callback_data", "triggerCallbackProcess=info," + v.getId());
                                return selection;
                            }, Collectors.toList())))
                    .values();
            reply_markup.put("inline_keyboard", root);
        }
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
