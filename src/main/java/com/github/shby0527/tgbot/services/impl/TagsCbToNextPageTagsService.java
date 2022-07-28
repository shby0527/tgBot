package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.constants.RedisKeyConstant;
import com.github.shby0527.tgbot.dao.InfoTagsMapper;
import com.github.shby0527.tgbot.entities.InfoTags;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.InlineCallbackService;
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
@Service("tagsCbToNextPageTags")
public class TagsCbToNextPageTagsService implements InlineCallbackService {

    @Autowired
    private IHttpService httpService;

    @Autowired
    private InfoTagsMapper infoTagsMapper;

    @Autowired
    private TelegramBotProperties botProperties;

    @Autowired
    private RedisTemplate<String, Map<String, Object>> redisTemplate;


    @Override
    public void process(String[] arguments, JsonNode origin) {
        if (arguments == null || arguments.length < 2) return;
        ValueOperations<String, Map<String, Object>> ops = redisTemplate.opsForValue();
        String key = RedisKeyConstant.getTagNextPageTo(arguments[1]);
        Map<String, Object> pagination = ops.get(key);
        if (pagination == null) return;
        String condition = (String) pagination.get("condition");
        Integer current = (Integer) pagination.get("current");
        Long id = 0L;
        List<Long> prevs = new ArrayList<>((List<Long>) pagination.get("prev"));
        if ("next".equals(arguments[0])) {
            id = (Long) pagination.get("next");
            prevs.add(id);
            current++;
        } else {
            prevs.remove(prevs.size() - 1);
            id = prevs.get(prevs.size() - 1);
            if (current > 1) {
                current--;
            }
        }
        List<InfoTags> tags = infoTagsMapper.selectByTags(condition, id, 10);
        Long messageId = JSONUtils.readJsonObject(origin, "callback_query.message.message_id", Long.class);
        Long chatId = JSONUtils.readJsonObject(origin, "callback_query.message.chat.id", Long.class);
        Map<String, Object> post = new HashMap<>();
        post.put("chat_id", chatId);
        post.put("message_id", messageId);
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
        List<Map<String, String>> paginationSelection = new ArrayList<>();
        pagination(condition,
                tags.stream()
                        .mapToLong(InfoTags::getId)
                        .max().orElse(0L),
                prevs,
                arguments[1], current);
        if (current > 1) {
            Map<String, String> nextSelection = new HashMap<>();
            nextSelection.put("text", "前へ");
            nextSelection.put("callback_data", "tagsCbToNextPageTags=prev," + arguments[1]);
            paginationSelection.add(nextSelection);
        }
        if (tags.size() >= 10) {
            Map<String, String> nextSelection = new HashMap<>();
            nextSelection.put("text", "次へ");
            nextSelection.put("callback_data", "tagsCbToNextPageTags=next," + arguments[1]);
            paginationSelection.add(nextSelection);
        }
        keyboard.add(paginationSelection);
        reply_markup.put("inline_keyboard", keyboard);
        String url = botProperties.getUrl() + "editMessageReplyMarkup";
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


    private void pagination(String condition, Long nextId, List<Long> prevId, String pagKey, Integer current) {
        String key = RedisKeyConstant.getTagNextPageTo(pagKey);
        ValueOperations<String, Map<String, Object>> ops = redisTemplate.opsForValue();
        Map<String, Object> pagination = new HashMap<>(2);
        pagination.put("condition", condition);
        pagination.put("next", nextId);
        pagination.put("current", current);
        pagination.put("prev", prevId);
        ops.set(key, pagination, 10, TimeUnit.MINUTES);
    }
}
