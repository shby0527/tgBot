package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.dao.UserInfoMapper;
import com.github.shby0527.tgbot.entities.Userinfo;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.InlineCallbackService;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.MessageSource;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service("changeLocaleCallbackProcess")
public class ChangeLocaleCallbackProcess implements InlineCallbackService {

    @Autowired
    private IHttpService httpService;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private MessageSource messageSource;

    @Autowired
    private TelegramBotProperties botProperties;


    private final Map<String, TypeProcessor> TYPE_PROCESSOR;

    private final Map<String, String> LANGUAGE_CODES;

    public ChangeLocaleCallbackProcess(Environment environment) {
        TYPE_PROCESSOR = Map.of(
                "list", this::listHandle,
                "set", this::setCodeHandle);
        Binder binder = Binder.get(environment);
        Bindable<Map<String, String>> mapBindable = Bindable.mapOf(String.class, String.class);
        BindResult<Map<String, String>> result = binder.bind("bot.languages", mapBindable);
        LANGUAGE_CODES = Collections.unmodifiableMap(result.orElse(Collections.emptyMap()));
    }

    @Override
    public void process(String[] arguments, JsonNode origin) {
        JsonNode from = JSONUtils.readJsonObject(origin, "callback_query.message.from", JsonNode.class);
        Long userId = from.get("id").longValue();
        Userinfo userinfo = userInfoMapper.selectByPrimaryKey(userId);
        if (userinfo == null) return;
        Locale locale = Locale.forLanguageTag(userinfo.getLanguageCode());
        TypeProcessor processor = TYPE_PROCESSOR.get(arguments[0]);
        if (processor == null) return;
        processor.handle(arguments, origin, locale, userinfo);
    }


    private void listHandle(String[] arguments, JsonNode origin, Locale locale, Userinfo userinfo) {
        Long messageId = JSONUtils.readJsonObject(origin, "callback_query.message.message_id", Long.class);
        Long chatId = JSONUtils.readJsonObject(origin, "callback_query.message.chat.id", Long.class);
        AtomicInteger increment = new AtomicInteger(0);
        Collection<List<Map<String, String>>> collection = LANGUAGE_CODES.entrySet()
                .stream()
                .collect(Collectors.groupingBy(p -> increment.getAndIncrement() % 4,
                        Collectors.mapping(p -> Map.of(
                                "text", p.getValue(),
                                "callback_data", "changeLocaleCallbackProcess=set," + p.getKey()
                        ), Collectors.toList())))
                .values();
        String text = messageSource.getMessage("replay.change-language.list", null, "replay.change-language.list", locale);
        editMessage(text, chatId, messageId, collection);
    }

    private void setCodeHandle(String[] arguments, JsonNode origin, Locale locale, Userinfo userinfo) {
        Long messageId = JSONUtils.readJsonObject(origin, "callback_query.message.message_id", Long.class);
        Long chatId = JSONUtils.readJsonObject(origin, "callback_query.message.chat.id", Long.class);
        String languageCode = arguments[1];
        userInfoMapper.updateUserLanguageCode(userinfo.getId(), languageCode);
        locale = Locale.forLanguageTag(languageCode);
        String text = messageSource.getMessage("replay.change-language.set", null, "replay.change-language.set", locale);
        editMessage(text, chatId, messageId, null);
    }


    private void editMessage(String text, Long chatId, Long messageId, Collection<List<Map<String, String>>> markup) {
        Map<String, Object> post = new HashMap<>();
        post.put("chat_id", chatId);
        post.put("message_id", messageId);
        post.put("text", text);
        if (markup != null && !markup.isEmpty()) {
            Map<String, Object> reply_markup = new HashMap<>(1);
            post.put("reply_markup", reply_markup);
            reply_markup.put("inline_keyboard", markup);
        }
        String url = botProperties.getUrl() + "editMessageText";
        try {
            String json = JSONUtils.OBJECT_MAPPER.writeValueAsString(post);
            log.debug("post data: {}", json);
            httpService.postForString(url, null, null, json, MediaType.APPLICATION_JSON_VALUE, httpResponse -> {
                try (httpResponse) {
                    log.debug("content:{}", httpResponse.getContent());
                } catch (IOException e) {
                    log.debug("", e);
                }
            });
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }


    private interface TypeProcessor {
        void handle(String[] arguments, JsonNode origin, Locale locale, Userinfo userinfo);
    }


}
