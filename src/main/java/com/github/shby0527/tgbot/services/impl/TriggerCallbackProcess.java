package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.dao.UserInfoMapper;
import com.github.shby0527.tgbot.dao.UserJobsMapper;
import com.github.shby0527.tgbot.entities.Userjobs;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.InlineCallbackService;
import com.xw.task.services.HttpResponse;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import com.xw.web.utils.StringReplaceUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Slf4j
@Service("triggerCallbackProcess")
public class TriggerCallbackProcess implements InlineCallbackService {

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private UserJobsMapper userJobsMapper;

    @Autowired
    private IHttpService httpService;

    @Autowired
    private TelegramBotProperties botProperties;

    private final Map<String, BiConsumer<JsonNode, Userjobs>> PROCESSOR_MAP;

    public TriggerCallbackProcess() {
        PROCESSOR_MAP = Map.of("list", this::list, "info", this::getInfo, "delete", this::delete);
    }


    @Override
    public void process(String[] arguments, JsonNode origin) {
        JsonNode from = JSONUtils.readJsonObject(origin, "callback_query.from", JsonNode.class);
        if (arguments.length < 2) return;
        Long jobId = Long.parseLong(arguments[1]);
        Userjobs userjobs = userJobsMapper.selectByPrimaryKey(jobId);
        if (userjobs == null) return;
        Long userId = from.get("id").longValue();
        if (!userjobs.getUserid().equals(userId)) return;
        String method = arguments[0];
        BiConsumer<JsonNode, Userjobs> consumer = PROCESSOR_MAP.getOrDefault(method, this::slice);
        consumer.accept(origin, userjobs);
    }


    private void getInfo(JsonNode origin, Userjobs job) {
        Long messageId = JSONUtils.readJsonObject(origin, "callback_query.message.message_id", Long.class);
        Long chatId = JSONUtils.readJsonObject(origin, "callback_query.message.chat.id", Long.class);
        ZonedDateTime time = Instant.ofEpochMilli(job.getNexttruck()).atZone(ZoneId.systemDefault());
        String formatTime = time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, String> info = new HashMap<>();
        info.put("name", job.getName());
        info.put("tag", job.getArguments());
        info.put("cron", job.getCorn());
        info.put("next", formatTime);
        String template = "ご主人さまのご計画は：\n名前：[name]\nキーワード：[tag]\n計画時間：[cron]\n次の時間：[next]";
        String finalText = StringReplaceUtils.replaceWithMap(template, info);
        log.debug("finalText is {}", finalText);
        List<List<Map<String, String>>> list = List.of(
                Collections.singletonList(
                        Map.of("text", "削除する",
                                "callback_data", "triggerCallbackProcess=delete," + job.getId())),
                Collections.singletonList(
                        Map.of("text", "戻る",
                                "callback_data", "triggerCallbackProcess=list," + job.getId()))
        );
        editMessage(finalText, chatId, messageId, list);
    }

    private void delete(JsonNode origin, Userjobs job) {
        Long messageId = JSONUtils.readJsonObject(origin, "callback_query.message.message_id", Long.class);
        Long chatId = JSONUtils.readJsonObject(origin, "callback_query.message.chat.id", Long.class);
        userJobsMapper.deleteById(job.getId());
        editMessage("削除完了しました", chatId, messageId, null);
    }

    private void list(JsonNode origin, Userjobs job) {
        // list 也得有个参数，当前返回时候的jobid
        Long messageId = JSONUtils.readJsonObject(origin, "callback_query.message.message_id", Long.class);
        Long chatId = JSONUtils.readJsonObject(origin, "callback_query.message.chat.id", Long.class);
        List<Userjobs> myChatJobs = userJobsMapper.getMyChatJobs(job.getUserid(), job.getChatid());
        AtomicInteger integer = new AtomicInteger(0);
        Collection<List<Map<String, String>>> root = myChatJobs.stream()
                .collect(Collectors.groupingBy(ignored -> integer.getAndIncrement() % 3,
                        Collectors.mapping(v -> {
                            Map<String, String> selection = new HashMap<>();
                            selection.put("text", v.getName());
                            selection.put("callback_data", "triggerCallbackProcess=info," + v.getId());
                            return selection;
                        }, Collectors.toList())))
                .values();
        editMessage("ご主人さまのご計画はこちらです", chatId, messageId, root);
    }

    private void slice(JsonNode origin, Userjobs job) {

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
            try (HttpResponse response = httpService.postForString(url, null, null, json, MediaType.APPLICATION_JSON_VALUE, null)) {
                JsonNode back = response.getJson();
                log.debug("return back {}", back);
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }
}
