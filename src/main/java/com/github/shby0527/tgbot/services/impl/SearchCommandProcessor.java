package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.constants.RedisKeyConstant;
import com.github.shby0527.tgbot.dao.InfoTagsMapper;
import com.github.shby0527.tgbot.entities.InfoTags;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.RegisterBotCommandService;
import com.xw.task.services.HttpResponse;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service("searchCommandProcessor")
public class SearchCommandProcessor implements RegisterBotCommandService {


    @Autowired
    private IHttpService httpService;

    @Autowired
    private InfoTagsMapper infoTagsMapper;
    @Autowired
    private TelegramBotProperties botProperties;

    @Autowired
    private RedisTemplate<String, Map<String, Object>> redisTemplate;

    @Override
    public void process(String[] arguments, JsonNode node) {
        String type = JSONUtils.readJsonObject(node, "message.chat.type", String.class);
        // 非私聊不处理
        if (!"private".equals(type)) return;
        if (arguments == null || arguments.length == 0) {
            sendText("何が探すの？教えて", node, null, null);
            return;
        }
        List<InfoTags> tags = infoTagsMapper.selectByTags(arguments[0], null, 10);
        if (tags.isEmpty()) {
            sendText("何も見えない、キツノリはご主人さまの野望が叶えないQvQ", node, null, null);
            return;
        }
        sendText("どちらはご主人様の野望です？", node, tags, arguments[0]);
    }


    private void sendText(String text, JsonNode origin, List<InfoTags> tags, String condition) {
        Map<String, Object> post = new HashMap<>();
        JsonNode chat = JSONUtils.readJsonObject(origin, "message.chat", JsonNode.class);
        JsonNode from = JSONUtils.readJsonObject(origin, "message.from", JsonNode.class);
        Long messageId = JSONUtils.readJsonObject(origin, "message.message_id", Long.class);
        post.put("reply_to_message_id", messageId);
        post.put("text", text + "\n @" + Optional.ofNullable(from.get("username")).map(JsonNode::textValue).orElse(""));
        post.put("chat_id", chat.get("id").longValue());
        if (tags != null && !tags.isEmpty()) {
            Map<String, Object> reply_markup = new HashMap<>(1);
            post.put("reply_markup", reply_markup);
            AtomicInteger integer = new AtomicInteger(0);
            Collection<List<Map<String, String>>> root = tags.stream()
                    .collect(Collectors.groupingBy(ignored -> integer.getAndIncrement() / 2,
                            Collectors.mapping(v -> {
                                Map<String, String> selection = new HashMap<>();
                                selection.put("text", v.getTag());
                                selection.put("callback_data", "tagsCbForNextImage=0," + v.getId());
                                return selection;
                            }, Collectors.toList())))
                    .values();
            List<List<Map<String, String>>> keyboard = new ArrayList<>(root);
            if (tags.size() >= 10) {
                Map<String, String> nextSelection = new HashMap<>();
                nextSelection.put("text", "次へ");
                nextSelection.put("callback_data", "tagsCbToNextPageTags=next," +
                        pagination(condition,
                                tags.stream()
                                        .mapToLong(InfoTags::getId)
                                        .max().orElse(0L)));
                keyboard.add(Collections.singletonList(nextSelection));
            }
            reply_markup.put("inline_keyboard", keyboard);
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


    private String pagination(String condition, Long lastId) {
        String uuid = UUID.randomUUID().toString();
        String key = RedisKeyConstant.getTagNextPageTo(uuid);
        ValueOperations<String, Map<String, Object>> ops = redisTemplate.opsForValue();
        Map<String, Object> pagination = new HashMap<>(2);
        pagination.put("condition", condition);
        pagination.put("next", lastId);
        pagination.put("current", 1);
        pagination.put("prev", Collections.singletonList(0L));
        ops.set(key, pagination, 10, TimeUnit.MINUTES);
        return uuid;
    }
}
