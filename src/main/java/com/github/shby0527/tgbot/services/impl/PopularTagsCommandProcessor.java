package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.constants.RedisKeyConstant;
import com.github.shby0527.tgbot.dao.TagToImgMapper;
import com.github.shby0527.tgbot.dao.UserInfoMapper;
import com.github.shby0527.tgbot.entities.TagPopular;
import com.github.shby0527.tgbot.entities.Userinfo;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.RegisterBotCommandService;
import com.xw.task.services.HttpResponse;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
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
@Service("popularTagsCommandProcessor")
public class PopularTagsCommandProcessor implements RegisterBotCommandService {

    @Autowired
    private TagToImgMapper tagToImgMapper;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private RedisTemplate<String, Collection<TagPopular>> redisTemplate;

    @Autowired
    private IHttpService httpService;

    @Autowired
    private TelegramBotProperties telegramBotProperties;

    @Autowired
    private MessageSource messageSource;

    @Override
    public void process(String[] arguments, JsonNode origin) {
        ValueOperations<String, Collection<TagPopular>> ops = redisTemplate.opsForValue();
        Collection<TagPopular> tagPopular = ops.get(RedisKeyConstant.POPULAR_TAGS);
        if (tagPopular == null) {
            tagPopular = tagToImgMapper.getTopOfTags(50);
            ops.set(RedisKeyConstant.POPULAR_TAGS, tagPopular, 30, TimeUnit.DAYS);
        }
        JsonNode chat = JSONUtils.readJsonObject(origin, "message.chat", JsonNode.class);
        JsonNode from = JSONUtils.readJsonObject(origin, "message.from", JsonNode.class);
        Long messageId = JSONUtils.readJsonObject(origin, "message.message_id", Long.class);
        Locale locale = getUserLocal(from);
        Map<String, Object> post = new HashMap<>();
        post.put("reply_to_message_id", messageId);
        String text = messageSource.getMessage("replay.tags.top", null, "replay.tags.top", locale);
        post.put("text", text + "\n @" + Optional.ofNullable(from.get("username")).map(JsonNode::textValue).orElse(""));
        post.put("chat_id", chat.get("id").longValue());
        Map<String, Object> reply_markup = new HashMap<>(1);
        post.put("reply_markup", reply_markup);
        AtomicInteger integer = new AtomicInteger(0);
        Collection<List<Map<String, String>>> root = tagPopular.stream()
                .sorted((a, b) -> RandomUtils.nextInt(1, 3) - 2)
                .limit(10)
                .collect(Collectors.groupingBy(ignored -> integer.getAndIncrement() / 2,
                        Collectors.mapping(v -> {
                            Map<String, String> selection = new HashMap<>();
                            selection.put("text", v.getTag());
                            selection.put("callback_data", "tagsCallbackProcess=" + v.getTagId());
                            return selection;
                        }, Collectors.toList())))
                .values();
        reply_markup.put("inline_keyboard", root);
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
