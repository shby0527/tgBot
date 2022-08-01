package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.constants.RedisKeyConstant;
import com.github.shby0527.tgbot.dao.ImgLinksMapper;
import com.github.shby0527.tgbot.dao.TagToImgMapper;
import com.github.shby0527.tgbot.dao.TgUploadedMapper;
import com.github.shby0527.tgbot.entities.ImgLinks;
import com.github.shby0527.tgbot.entities.InfoTags;
import com.github.shby0527.tgbot.entities.TagFoImgKey;
import com.github.shby0527.tgbot.entities.TgUploaded;
import com.github.shby0527.tgbot.properties.Aria2Properties;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.InlineCallbackService;
import com.github.shby0527.tgbot.websocket.Aria2WebSocketHandler;
import com.xw.task.services.HttpResponse;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service("tagsCbForNextImage")
public class TagsCbForNextImageService implements InlineCallbackService {

    @Autowired
    private IHttpService httpService;

    @Autowired
    private TelegramBotProperties botProperties;

    @Autowired
    private Aria2Properties aria2Properties;

    @Autowired
    private ImgLinksMapper imgLinksMapper;

    @Autowired
    private TagToImgMapper tagToImgMapper;

    @Autowired
    private TgUploadedMapper tgUploadedMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public void process(String[] arguments, JsonNode origin) {
        Long nextId = Long.parseLong(arguments[0]);
        Long tagId = Long.parseLong(arguments[1]);
        TagFoImgKey imageKey = tagToImgMapper.getImageKey(tagId, nextId);
        if (imageKey == null) {
            sendText("もうないよ", origin);
            Long chatId = JSONUtils.readJsonObject(origin, "callback_query.message.chat.id", Long.class);
            Long messageId = JSONUtils.readJsonObject(origin, "callback_query.message.message_id", Long.class);
            clearMessageKeyboard(chatId, messageId);
            return;
        }
        ImgLinks links = imgLinksMapper.selectByPrimaryKey(imageKey.getImgid());
        TgUploaded tgUploaded = tgUploadedMapper.selectByPrimaryKey(links.getId());
        if (tgUploaded != null) {
            sendExistsImage(links, tgUploaded, origin, tagId);
            return;
        }
        sendDownloadedImage(links, origin, tagId);

    }


    private void sendDownloadedImage(ImgLinks links, JsonNode node, Long tagId) {
        JsonNode message = JSONUtils.readJsonObject(node, "callback_query.message", JsonNode.class);
        JsonNode replay = null;
        if (!message.has("document") && !message.has("video")) {
            replay = editMessage("ダウロード中、しばらくお待ち下さい", node);
        } else {
            replay = sendText("ダウロード中、しばらくお待ち下さい", node);
            Long chatId = JSONUtils.readJsonObject(node, "callback_query.message.chat.id", Long.class);
            Long messageId = JSONUtils.readJsonObject(node, "callback_query.message.message_id", Long.class);
            clearMessageKeyboard(chatId, messageId);
        }
        Map<String, Object> saveStatus = new HashMap<>(2);
        saveStatus.put("service", "selectionNextImage");
        saveStatus.put("image", links);
        saveStatus.put("selection", node);
        saveStatus.put("tagId", tagId);
        saveStatus.put("replay", replay);
        String key = RedisKeyConstant.getWaitingDownloadImage(links.getId());
        ValueOperations<String, Object> ops = redisTemplate.opsForValue();
        ops.set(key, saveStatus);
        // 通过websocket 开始下载
        try {
            WebSocketSession session = Aria2WebSocketHandler.getSession();
            Map<String, Object> addUrl = new HashMap<>();
            Map<String, Object> params = new HashMap<>();
            int latestIndex = links.getLink().lastIndexOf('.');
            String ext = links.getLink().substring(latestIndex);
            params.put("out", links.getId().toString() + ext);
            addUrl.put("jsonrpc", "2.0");
            addUrl.put("id", "addrpc-" + links.getId());
            addUrl.put("method", "aria2.addUri");
            addUrl.put("params", new Object[]{
                    "token:" + aria2Properties.getToken(),
                    new String[]{links.getLink()},
                    params
            });
            TextMessage textMessage = new TextMessage(JSONUtils.OBJECT_MAPPER.writeValueAsBytes(addUrl));
            session.sendMessage(textMessage);
        } catch (IOException e) {
            log.debug("获取 session 失败", e);
            editMessage("ご主人さまの探しものがなくなっちゃった、うぅぅぅぅQVQ", node);
        }
    }

    private JsonNode sendText(String text, JsonNode origin) {
        Map<String, Object> post = new HashMap<>();
        JsonNode chat = JSONUtils.readJsonObject(origin, "callback_query.message.chat", JsonNode.class);
        post.put("text", text);
        post.put("chat_id", chat.get("id").longValue());
        String url = botProperties.getUrl() + "sendMessage";
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

    private void clearMessageKeyboard(Long chatId, Long messageId) {
        String url = botProperties.getUrl() + "editMessageReplyMarkup";
        Map<String, Object> post = new HashMap<>();
        post.put("chat_id", chatId);
        post.put("message_id", messageId);
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

    private JsonNode editMessage(String text, JsonNode origin) {
        Map<String, Object> post = new HashMap<>();
        Long chatId = JSONUtils.readJsonObject(origin, "callback_query.message.chat.id", Long.class);
        Long messageId = JSONUtils.readJsonObject(origin, "callback_query.message.message_id", Long.class);
        post.put("chat_id", chatId);
        post.put("message_id", messageId);
        post.put("text", text);
        String url = botProperties.getUrl() + "editMessageText";
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


    private void sendExistsImage(ImgLinks links, TgUploaded uploaded, JsonNode origin, Long tagId) {

        Long chatId = JSONUtils.readJsonObject(origin, "callback_query.message.chat.id", Long.class);
        Long messageId = JSONUtils.readJsonObject(origin, "callback_query.message.message_id", Long.class);
        List<InfoTags> tags = tagToImgMapper.getImagesTags(uploaded.getImgid());
        Map<String, Object> post = new HashMap<>();
        post.put("chat_id", chatId);
        post.put("caption", MessageFormat.format("\nAuthor: {0} \n{1}x{2} \n tags: {3}",
                Optional.ofNullable(links.getAuthor()).orElse("无"),
                Optional.ofNullable(links.getWidth()).orElse(0),
                Optional.ofNullable(links.getHeight()).orElse(0),
                tags.stream().limit(5).map(InfoTags::getTag).collect(Collectors.joining(" , "))));
        post.put("document", uploaded.getTgid());
        Map<String, Object> reply_markup = new HashMap<>(1);
        post.put("reply_markup", reply_markup);
        List<List<Map<String, String>>> keyboard = new ArrayList<>();
        Map<String, String> selection = new HashMap<>();
        selection.put("text", "次をちょうだい");
        selection.put("callback_data", "tagsCbForNextImage=" + links.getId() + "," + tagId);
        keyboard.add(Collections.singletonList(selection));
        reply_markup.put("inline_keyboard", keyboard);
        String url = botProperties.getUrl() + "sendDocument";
        try {
            String json = JSONUtils.OBJECT_MAPPER.writeValueAsString(post);
            log.debug("post data: {}", json);
            try (HttpResponse response = httpService.postForString(url, null, null, json, MediaType.APPLICATION_JSON_VALUE, null)) {
                String back = response.getContent();
                log.debug("return back {}", back);
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        JsonNode message = JSONUtils.readJsonObject(origin, "callback_query.message", JsonNode.class);
        if (message.has("document")) {
            clearMessageKeyboard(chatId, messageId);
        } else {
            deleteMessage(chatId, messageId);
        }
    }

    private void deleteMessage(Long chatId, Long messageId) {
        Map<String, Object> post = new HashMap<>();
        post.put("chat_id", chatId);
        post.put("message_id", messageId);
        String url = botProperties.getUrl() + "deleteMessage";
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
