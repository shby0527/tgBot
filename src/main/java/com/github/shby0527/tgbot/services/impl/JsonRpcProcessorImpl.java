package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.constants.RedisKeyConstant;
import com.github.shby0527.tgbot.dao.TagToImgMapper;
import com.github.shby0527.tgbot.dao.TgUploadedMapper;
import com.github.shby0527.tgbot.dao.UserInfoMapper;
import com.github.shby0527.tgbot.entities.ImgLinks;
import com.github.shby0527.tgbot.entities.InfoTags;
import com.github.shby0527.tgbot.entities.TgUploaded;
import com.github.shby0527.tgbot.properties.Aria2Properties;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.JsonRpcProcessor;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class JsonRpcProcessorImpl implements JsonRpcProcessor {

    @Autowired
    private TgUploadedMapper tgUploadedMapper;

    @Autowired
    private TagToImgMapper tagToImgMapper;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private IHttpService httpService;

    @Autowired
    private TelegramBotProperties telegramBotProperties;

    @Autowired
    private Aria2Properties aria2Properties;

    @Autowired
    private MessageSource messageSource;

    private final static Pattern PATTERN = Pattern.compile("^([a-zA-Z]+)-\\d+$");

    private final Map<String, BiConsumer<WebSocketSession, JsonNode>> METHOD_CALL;


    private final Map<String, BiFunction<String, Map<String, Object>, JsonNode>> SEND_DOCUMENT_PARAMETERS;

    public JsonRpcProcessorImpl() {
        METHOD_CALL = new HashMap<>();
        METHOD_CALL.put("aria2.onDownloadError", this::downloadError);
        METHOD_CALL.put("aria2.onDownloadComplete", this::downloadComplete);
        SEND_DOCUMENT_PARAMETERS = new HashMap<>();
        SEND_DOCUMENT_PARAMETERS.put("chatRandomCallbackService", this::chatRandomParameters);
        SEND_DOCUMENT_PARAMETERS.put("scheduledCallbackService", this::scheduledCallbackService);
        SEND_DOCUMENT_PARAMETERS.put("selectionNextImage", this::selectionNextImage);
    }


    @Override
    @Async("statsExecutor")
    public void process(WebSocketSession session, String json) {
        try {
            JsonNode jsonBack = JSONUtils.OBJECT_MAPPER.readTree(json);
            String id = Optional.ofNullable(jsonBack.get("id")).map(JsonNode::textValue).orElse("");
            if (StringUtils.isEmpty(id)) {
                String method = jsonBack.get("method").textValue();
                BiConsumer<WebSocketSession, JsonNode> consumer = METHOD_CALL.get(method);
                if (consumer != null) consumer.accept(session, jsonBack);
            }
        } catch (IOException e) {
            log.error("处理出错", e);
        }
    }


    private void getFileCallback(JsonNode json) {
        log.debug("{}", json);
        ValueOperations<String, Object> ops = redisTemplate.opsForValue();
        String path = JSONUtils.readJsonObject(json, "result[0].path", String.class);
        File file = new File(path);
        String name = file.getName();
        int index = name.lastIndexOf('.');
        String id = name.substring(0, index);
        String key = RedisKeyConstant.getWaitingDownloadImage(Long.valueOf(id));
        Map<String, Object> map = (Map<String, Object>) ops.get(key);
        if (map == null) return;
        redisTemplate.delete(key);
        Object service = map.get("service");
        if (service == null) return;
        BiFunction<String, Map<String, Object>, JsonNode> function = SEND_DOCUMENT_PARAMETERS.get(service);
        if (function == null) return;
        JsonNode backJson = function.apply(path, map);
        log.debug("back json {}", backJson);
        if (Optional.ofNullable(backJson).map(t -> t.get("ok")).map(JsonNode::booleanValue).orElse(false)) {
            TgUploaded tgUploaded = new TgUploaded();
            tgUploaded.setImgid(Long.valueOf(id));
            tgUploaded.setStatus((byte) 1);
            tgUploaded.setTgid(JSONUtils.readJsonObject(backJson, "result.document.file_id", String.class));
            tgUploadedMapper.insertSelective(tgUploaded);
        }
    }

    private void getFileFailCallback(JsonNode json) {
        log.debug("{}", json);
        ValueOperations<String, Object> ops = redisTemplate.opsForValue();
        String path = JSONUtils.readJsonObject(json, "result[0].path", String.class);
        File file = new File(path);
        String name = file.getName();
        int index = name.lastIndexOf('.');
        String id = name.substring(0, index);
        String key = RedisKeyConstant.getWaitingDownloadImage(Long.valueOf(id));
        Map<String, Object> map = (Map<String, Object>) ops.get(key);
        if (map == null) return;
        redisTemplate.delete(key);
        JsonNode rep = (JsonNode) map.get("replay");
        Locale locale = Optional.ofNullable(map.get("language"))
                .map(Object::toString)
                .map(Locale::forLanguageTag)
                .orElse(Locale.JAPAN);
        Long scc = (Long) map.get("scc");
        if (scc == null) {
            Long chatId = JSONUtils.readJsonObject(rep, "result.chat.id", Long.class);
            Long messageId = JSONUtils.readJsonObject(rep, "result.message_id", Long.class);
            editMessage(messageSource.getMessage("bk.message.download-fail", null, "bk.message.download-fail", locale),
                    chatId, messageId);
        }
    }


    private void downloadComplete(WebSocketSession session, JsonNode json) {
        String gid = JSONUtils.readJsonObject(json, "params[0].gid", String.class);
        Map<String, Object> addUrl = new HashMap<>();
        addUrl.put("jsonrpc", "2.0");
        addUrl.put("id", "getFile-" + RandomUtils.nextLong(0, 1000));
        addUrl.put("method", "aria2.getFiles");
        addUrl.put("params", new Object[]{
                "token:" + aria2Properties.getToken(),
                gid
        });
        try {
            String post = JSONUtils.OBJECT_MAPPER.writeValueAsString(addUrl);
            httpService.postForString(aria2Properties.getHttp(), null, null, post, MediaType.APPLICATION_JSON_VALUE, httpResponse -> {
                try (httpResponse) {
                    this.getFileCallback(httpResponse.getJson());
                } catch (IOException e) {
                    log.debug("", e);
                }
            });
        } catch (IOException e) {
            log.error("出现错误", e);
        }
    }

    private void downloadError(WebSocketSession session, JsonNode json) {
        String gid = JSONUtils.readJsonObject(json, "params[0].gid", String.class);
        Map<String, Object> addUrl = new HashMap<>();
        addUrl.put("jsonrpc", "2.0");
        addUrl.put("id", "getFileFail-" + RandomUtils.nextLong(0, 1000));
        addUrl.put("method", "aria2.getFiles");
        addUrl.put("params", new Object[]{
                "token:" + aria2Properties.getToken(),
                gid
        });
        try {
            String post = JSONUtils.OBJECT_MAPPER.writeValueAsString(addUrl);
            httpService.postForString(aria2Properties.getHttp(), null, null, post, MediaType.APPLICATION_JSON_VALUE, httpResponse -> {
                try (httpResponse) {
                    this.getFileFailCallback(httpResponse.getJson());
                } catch (IOException e) {
                    log.debug("", e);
                }
            });
        } catch (IOException e) {
            log.error("出现错误", e);
        }
    }


    private void editMessage(String text, Long chatId, Long messageId) {
        Map<String, Object> post = new HashMap<>();
        post.put("chat_id", chatId);
        post.put("message_id", messageId);
        post.put("text", text);
        String url = telegramBotProperties.getUrl() + "editMessageText";
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


    private JsonNode selectionNextImage(String path, Map<String, Object> status) {
        ImgLinks image = (ImgLinks) status.get("image");
        JsonNode replay = (JsonNode) status.get("replay");
        Locale locale = Optional.ofNullable(status.get("language"))
                .map(Object::toString)
                .map(Locale::forLanguageTag)
                .orElse(Locale.JAPAN);
        Long tagId = (Long) status.get("tagId");
        Long chatId = JSONUtils.readJsonObject(replay, "result.chat.id", Long.class);
        Long messageId = JSONUtils.readJsonObject(replay, "result.message_id", Long.class);
        editMessage(messageSource.getMessage("bk.message.download-completed", null, "bk.message.download-completed", locale),
                chatId, messageId);
        Map<String, Object> post = new HashMap<>();
        List<InfoTags> tags = tagToImgMapper.getImagesTags(image.getId());
        post.put("chat_id", chatId);
        post.put("caption", MessageFormat.format("\nAuthor: {0} \n{1}x{2} \n tags: {3}",
                Optional.ofNullable(image.getAuthor()).orElse("无"),
                Optional.ofNullable(image.getWidth()).orElse(0),
                Optional.ofNullable(image.getHeight()).orElse(0),
                tags.stream()
                        .limit(5)
                        .map(InfoTags::getTag)
                        .collect(Collectors.joining(" , #", "#", ""))));
        Map<String, Object> reply_markup = new HashMap<>(1);
        post.put("reply_markup", reply_markup);
        List<List<Map<String, String>>> keyboard = new ArrayList<>();
        Map<String, String> np = new HashMap<>();
        np.put("text", messageSource.getMessage("bk.message.to-next", null, "bk.message.to-next", locale));
        np.put("callback_data", "tagsCbForNextImage=" + image.getId() + "," + tagId);
        keyboard.add(Collections.singletonList(np));
        reply_markup.put("inline_keyboard", keyboard);
        JsonNode backJson = sendDocument("file://" + path, post);
        deleteMessage(chatId, messageId);
        return backJson;
    }


    private JsonNode chatRandomParameters(String path, Map<String, Object> status) {
        ImgLinks links = (ImgLinks) status.get("image");
        JsonNode origin = (JsonNode) status.get("chat");
        Locale locale = Optional.ofNullable(status.get("language"))
                .map(Object::toString)
                .map(Locale::forLanguageTag)
                .orElse(Locale.JAPAN);
        JsonNode rep = (JsonNode) status.get("replay");
        Long rChatId = JSONUtils.readJsonObject(rep, "result.chat.id", Long.class);
        Long rMessageId = JSONUtils.readJsonObject(rep, "result.message_id", Long.class);
        editMessage(messageSource.getMessage("bk.message.download-completed", null, "bk.message.download-completed", locale),
                rChatId, rMessageId);
        Map<String, Object> post = new HashMap<>();
        List<InfoTags> tags = tagToImgMapper.getImagesTags(links.getId());
        JsonNode chat = JSONUtils.readJsonObject(origin, "message.chat", JsonNode.class);
        JsonNode from = JSONUtils.readJsonObject(origin, "message.from", JsonNode.class);
        Long messageId = JSONUtils.readJsonObject(origin, "message.message_id", Long.class);
        post.put("reply_to_message_id", messageId);
        post.put("chat_id", chat.get("id").longValue());
        post.put("caption", MessageFormat.format("@{0} \nAuthor: {1} \n{2}x{3}, \ntags: {4}",
                Optional.ofNullable(from.get("username")).map(JsonNode::textValue).orElse(""),
                Optional.ofNullable(links.getAuthor()).orElse("无"),
                Optional.ofNullable(links.getWidth()).orElse(0),
                Optional.ofNullable(links.getHeight()).orElse(0),
                tags.stream().limit(5).map(InfoTags::getTag).collect(Collectors.joining(" , #", "#", ""))));
        JsonNode backJson = sendDocument("file://" + path, post);
        Long chatId = JSONUtils.readJsonObject(origin, "message.chat.id", Long.class);
        deleteMessage(chatId, rMessageId);
        return backJson;
    }

    private JsonNode scheduledCallbackService(String path, Map<String, Object> status) {
        Long scc = (Long) status.get("scc");
        Map<String, Object> post = new HashMap<>();
        post.put("chat_id", scc);
        return sendDocument("file://" + path, post);
    }


    private JsonNode sendDocument(String path, Map<String, Object> post) {

        post.put("document", path);
        String url = telegramBotProperties.getUrl() + "sendDocument";
        try {
            String json = JSONUtils.OBJECT_MAPPER.writeValueAsString(post);
            log.debug("post data: {}", json);
            Mono<JsonNode> mono = Mono.create(sink -> {
                try {
                    httpService.postForString(url, null, null, json, MediaType.APPLICATION_JSON_VALUE, httpResponse -> {
                        try (httpResponse) {
                            JsonNode back = httpResponse.getJson();
                            log.debug("return back {}", back);
                            sink.success(back);
                            return;
                        } catch (IOException e) {
                            log.error(e.getMessage(), e);
                        }
                        sink.success();
                    });
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            });
            return mono
                    .checkpoint("send Document")
                    .blockOptional(Duration.ofMinutes(5))
                    .orElse(null);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }


    public void deleteMessage(Long chatId, Long messageId) {
        Map<String, Object> post = new HashMap<>();

        post.put("chat_id", chatId);

        post.put("message_id", messageId);
        String url = telegramBotProperties.getUrl() + "deleteMessage";
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
}
