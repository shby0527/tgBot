package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.constants.RedisKeyConstant;
import com.github.shby0527.tgbot.dao.TagToImgMapper;
import com.github.shby0527.tgbot.dao.TgUploadedMapper;
import com.github.shby0527.tgbot.entities.ImgLinks;
import com.github.shby0527.tgbot.entities.InfoTags;
import com.github.shby0527.tgbot.entities.TgUploaded;
import com.github.shby0527.tgbot.properties.Aria2Properties;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.JsonRpcProcessor;
import com.xw.task.services.HttpResponse;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
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
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private IHttpService httpService;

    @Autowired
    private TelegramBotProperties telegramBotProperties;

    @Autowired
    private Aria2Properties aria2Properties;

    private final static Pattern PATTERN = Pattern.compile("^([a-zA-Z]+)-\\d+$");

    private final Map<String, BiConsumer<WebSocketSession, JsonNode>> CALLBACK;

    private final Map<String, BiConsumer<WebSocketSession, JsonNode>> METHOD_CALL;

    public JsonRpcProcessorImpl() {
        CALLBACK = new HashMap<>();
        CALLBACK.put("getFile", this::getFileCallback);
        CALLBACK.put("getFileFail", this::getFileFailCallback);
        METHOD_CALL = new HashMap<>();
        METHOD_CALL.put("aria2.onDownloadError", this::downloadError);
        METHOD_CALL.put("aria2.onDownloadComplete", this::downloadComplete);
    }


    @Override
    @Async("statsExecutor")
    public void process(WebSocketSession session, String json) {
        try {
            JsonNode jsonBack = JSONUtils.OBJECT_MAPPER.readTree(json);
            String id = Optional.ofNullable(jsonBack.get("id")).map(JsonNode::textValue).orElse("");
            if (StringUtils.isNotEmpty(id)) {
                Matcher matcher = PATTERN.matcher(id);
                if (matcher.find()) {
                    String back = matcher.group(1);
                    BiConsumer<WebSocketSession, JsonNode> consumer = CALLBACK.get(back);
                    if (consumer != null) consumer.accept(session, jsonBack);
                }
            } else {
                String method = jsonBack.get("method").textValue();
                BiConsumer<WebSocketSession, JsonNode> consumer = METHOD_CALL.get(method);
                if (consumer != null) consumer.accept(session, jsonBack);
            }
        } catch (IOException e) {
            log.error("处理出错", e);
        }
    }


    private void getFileCallback(WebSocketSession session, JsonNode json) {
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
        Long scc = (Long) map.get("scc");
        ImgLinks links = (ImgLinks) map.get("image");
        JsonNode chat = (JsonNode) map.get("chat");
        JsonNode rep = (JsonNode) map.get("replay");
        rep = editMessage("ご主人さまの捜し物はまもなくお届けます", rep);
        JsonNode backJson = sendDocument("file://" + path, links, chat, scc);
        deleteMessage(chat, rep);
        log.debug("back json {}", backJson);
        if (Optional.ofNullable(backJson).map(t -> t.get("ok")).map(JsonNode::booleanValue).orElse(false)) {
            TgUploaded tgUploaded = new TgUploaded();
            tgUploaded.setImgid(Long.valueOf(id));
            tgUploaded.setStatus((byte) 1);
            tgUploaded.setTgid(JSONUtils.readJsonObject(backJson, "result.document.file_id", String.class));
            tgUploadedMapper.insertSelective(tgUploaded);
        }
    }

    private void getFileFailCallback(WebSocketSession session, JsonNode json) {
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
//        JsonNode chat = (JsonNode) map.get("chat");
        JsonNode rep = (JsonNode) map.get("replay");
        Long scc = (Long) map.get("scc");
        if (scc == null) {
            editMessage("ごめんなさい、なくしちゃだ　QVQ", rep);
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
            TextMessage textMessage = new TextMessage(JSONUtils.OBJECT_MAPPER.writeValueAsBytes(addUrl));
            session.sendMessage(textMessage);
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
            TextMessage textMessage = new TextMessage(JSONUtils.OBJECT_MAPPER.writeValueAsBytes(addUrl));
            session.sendMessage(textMessage);
        } catch (IOException e) {
            log.error("出现错误", e);
        }
    }


    private JsonNode editMessage(String text, JsonNode origin) {
        Map<String, Object> post = new HashMap<>();
        Long chatId = JSONUtils.readJsonObject(origin, "result.chat.id", Long.class);
        Long messageId = JSONUtils.readJsonObject(origin, "result.message_id", Long.class);
        post.put("chat_id", chatId);
        post.put("message_id", messageId);
        post.put("text", text);
        String url = telegramBotProperties.getUrl() + "editMessageText";
        try {
            String json = JSONUtils.OBJECT_MAPPER.writeValueAsString(post);
            log.debug("post data: {}", json);
            try (HttpResponse response = httpService.postForString(url, null, null, json, MediaType.APPLICATION_JSON_VALUE, null)) {
                JsonNode back = response.getJson();
                log.debug("return back {}", back);
                return back;
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }


//    private JsonNode sendText(String text, JsonNode origin) {
//        Map<String, Object> post = new HashMap<>();
//        JsonNode chat = JSONUtils.readJsonObject(origin, "message.chat", JsonNode.class);
//        JsonNode from = JSONUtils.readJsonObject(origin, "message.from", JsonNode.class);
//        Long messageId = JSONUtils.readJsonObject(origin, "message.message_id", Long.class);
//        post.put("reply_to_message_id", messageId);
//        post.put("text", text + "\n @" + Optional.ofNullable(from.get("username")).map(JsonNode::textValue).orElse(""));
//        post.put("chat_id", chat.get("id").longValue());
//        String url = telegramBotProperties.getUrl() + "sendMessage";
//        try {
//            String json = JSONUtils.OBJECT_MAPPER.writeValueAsString(post);
//            log.debug("post data: {}", json);
//            try (HttpResponse response = httpService.postForString(url, null, null, json, MediaType.APPLICATION_JSON_VALUE, null)) {
//                JsonNode back = response.getJson();
//                log.debug("return back {}", back);
//                return back;
//            }
//        } catch (IOException e) {
//            log.error(e.getMessage(), e);
//        }
//        return null;
//    }


    private JsonNode sendDocument(String picUrl, ImgLinks links, JsonNode origin, Long scc) {
        Map<String, Object> post = new HashMap<>();
        if (scc == null) {
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
                    tags.stream().limit(5).map(InfoTags::getTag).collect(Collectors.joining(" , "))));
        } else {
            post.put("chat_id", scc);
        }
        post.put("document", picUrl);
        String url = telegramBotProperties.getUrl() + "sendDocument";
        try {
            String json = JSONUtils.OBJECT_MAPPER.writeValueAsString(post);
            log.debug("post data: {}", json);
            try (HttpResponse response = httpService.postForString(url, null, null, json, MediaType.APPLICATION_JSON_VALUE, null)) {
                JsonNode back = response.getJson();
                log.debug("return back {}", back);
                return back;
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }


    public void deleteMessage(JsonNode origin, JsonNode chat) {
        Map<String, Object> post = new HashMap<>();
        Long chatId = JSONUtils.readJsonObject(origin, "message.chat.id", Long.class);
        post.put("chat_id", chatId);
        Long messageId = JSONUtils.readJsonObject(chat, "result.message_id", Long.class);
        post.put("message_id", messageId);
        String url = telegramBotProperties.getUrl() + "deleteMessage";
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
