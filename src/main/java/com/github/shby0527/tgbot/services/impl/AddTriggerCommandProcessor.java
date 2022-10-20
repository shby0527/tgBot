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
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;

@Slf4j
@Service("addTriggerCommandProcessor")
public class AddTriggerCommandProcessor implements RegisterBotCommandService {

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private UserJobsMapper userJobsMapper;

    @Autowired
    private IHttpService httpService;

    @Autowired
    private TelegramBotProperties botProperties;

    @Autowired
    private MessageSource messageSource;

    private static final String SERVICE_NAME = "autoSendSchedulerService";

    @Override
    public void process(String[] arguments, JsonNode node) {
        JsonNode chat = JSONUtils.readJsonObject(node, "message.chat", JsonNode.class);
        JsonNode from = JSONUtils.readJsonObject(node, "message.from", JsonNode.class);
        Long userId = from.get("id").longValue();
        Userinfo userinfo = userInfoMapper.selectByPrimaryKey(userId);
        if (userinfo == null) return;
        String type = chat.get("type").textValue();
        Long chatId = chat.get("id").longValue();
        if (!"private".equals(type)) {
            if (!checkChatAdmin(userId, chatId)) {
                // 只要不是私聊频道，权限过低的都不允许创建
                if (userinfo.getPermission() < 2) return;
            }
            return;
        }
        if (arguments.length < 3) {
            sendText("arguments not enough, look /help", node);
            return;
        }
        Locale locale = Locale.forLanguageTag(userinfo.getLanguageCode());
        ZonedDateTime now = ZonedDateTime.now();
        String name = arguments[0];
        String search = arguments[1];
        String cron;
        long nextTruck = 0;
        String errorMsg = messageSource.getMessage("replay.trigger.error", null, "replay.trigger.error", locale);
        if (StringUtils.startsWithIgnoreCase(arguments[2], "P")) {
            // P 开头认为是 Duration 表达式
            try {
                Duration duration = Duration.parse(arguments[2]);
                cron = arguments[2];
                long nowT = Date.from(now.toInstant()).getTime();
                nextTruck = nowT + duration.getSeconds() * 1000 - 10;
            } catch (Throwable t) {
                sendText(errorMsg, node);
                return;
            }
        } else {
            // 不是Duration 就是 Cron 表达式， cron 表达式，需要后续参数
            if (arguments.length < 8) {
                sendText(errorMsg, node);
                return;
            }
            cron = String.format("%s %s %s %s %s %s",
                    arguments[2], arguments[3], arguments[4],
                    arguments[5], arguments[6], arguments[7]);
            if (!CronExpression.isValidExpression(cron)) {
                sendText(errorMsg, node);
                return;
            }
            CronExpression expression = CronExpression.parse(cron);
            ZonedDateTime next = expression.next(now);
            if (next == null) {
                sendText(errorMsg, node);
                return;
            }
            nextTruck = Date.from(next.toInstant()).getTime() - 10;
        }
        Userjobs userjobs = new Userjobs();
        userjobs.setUserid(userId);
        userjobs.setScheduler(SERVICE_NAME);
        userjobs.setChatid(chatId);
        userjobs.setArguments(search);
        userjobs.setCorn(cron);
        userjobs.setNexttruck(nextTruck);
        userjobs.setName(name);
        userJobsMapper.insertSelective(userjobs);
        sendText(messageSource.getMessage("replay.trigger.finish", null, "replay.trigger.finish", locale), node);
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


    private boolean checkChatAdmin(Long userId, Long chatId) {
        String url = botProperties.getUrl() + "getChatAdministrators";
        try (HttpResponse response = httpService.get(url, null, Map.of("chat_id", chatId.toString()), null)) {
            JsonNode back = response.getJson();
            log.debug("return back {}", back);

            final JsonNode result = back.get("result");
            if (result.isArray()) {
                for (int i = 0; i < result.size(); i++) {
                    final JsonNode item = result.get(i);
                    final JsonNode user = item.get("user");
                    final JsonNode id = user.get("id");
                    if (id.asLong() == userId) {
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            log.error("error", e);
            return false;
        }
        return false;
    }
}
