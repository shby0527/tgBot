package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.constants.RedisKeyConstant;
import com.github.shby0527.tgbot.dao.ImgLinksMapper;
import com.github.shby0527.tgbot.dao.TagToImgMapper;
import com.github.shby0527.tgbot.dao.TgUploadedMapper;
import com.github.shby0527.tgbot.dao.UserInfoMapper;
import com.github.shby0527.tgbot.entities.*;
import com.github.shby0527.tgbot.properties.Aria2Properties;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.InlineCallbackService;
import com.xw.task.services.HttpResponse;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

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
    private UserInfoMapper userInfoMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private MessageSource messageSource;

    @Override
    public void process(String[] arguments, JsonNode origin) {
        Long nextId = Long.parseLong(arguments[0]);
        Long tagId = Long.parseLong(arguments[1]);
        TagFoImgKey imageKey = tagToImgMapper.getImageKey(tagId, nextId);
        JsonNode from = JSONUtils.readJsonObject(origin, "callback_query.message.from", JsonNode.class);
        Locale locale = getUserLocal(from);
        if (imageKey == null) {
            sendText(messageSource.getMessage("replay.next-image.no-more-image", null, "replay.next-image.no-more-image", locale), origin);
            Long chatId = JSONUtils.readJsonObject(origin, "callback_query.message.chat.id", Long.class);
            Long messageId = JSONUtils.readJsonObject(origin, "callback_query.message.message_id", Long.class);
            clearMessageKeyboard(chatId, messageId);
            return;
        }
        ImgLinks links = imgLinksMapper.selectByPrimaryKey(imageKey.getImgid());
        TgUploaded tgUploaded = tgUploadedMapper.selectByPrimaryKey(links.getId());
        if (tgUploaded != null) {
            sendExistsImage(links, tgUploaded, origin, tagId, locale);
            return;
        }
        sendDownloadedImage(links, origin, tagId, locale);
    }

    private synchronized void sendDownloadedImage(ImgLinks links, JsonNode node, Long tagId, Locale locale) {
        JsonNode message = JSONUtils.readJsonObject(node, "callback_query.message", JsonNode.class);
        JsonNode replay = null;
        String key = RedisKeyConstant.getWaitingDownloadImage(links.getId());
        if (Optional.ofNullable(redisTemplate.hasKey(key)).orElse(false)) {
            return;
        }
        if (!message.has("document") && !message.has("video")) {
            replay = editMessage(messageSource.getMessage("replay.next-image.downloading", null, "replay.next-image.downloading", locale), node);
        } else {
            replay = sendText(messageSource.getMessage("replay.next-image.downloading", null, "replay.next-image.downloading", locale), node);
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
        ValueOperations<String, Object> ops = redisTemplate.opsForValue();
        ops.set(key, saveStatus);
        // 通过websocket 开始下载
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
        try {
            String post = JSONUtils.OBJECT_MAPPER.writeValueAsString(addUrl);
            httpService.postForString(aria2Properties.getHttp(), null, null, post, MediaType.APPLICATION_JSON_VALUE, httpResponse -> {
                try (httpResponse) {
                    log.debug("content:{}", httpResponse.getContent());
                } catch (IOException e) {
                    log.debug("", e);
                }
            });
        } catch (IOException e) {
            log.debug("获取 session 失败", e);
            editMessage(messageSource.getMessage("replay.exception", null, "replay.exception", locale), node);
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


    private void sendExistsImage(ImgLinks links, TgUploaded uploaded, JsonNode origin, Long tagId, Locale locale) {
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
        selection.put("text", messageSource.getMessage("replay.next-image.next-image", null, "replay.next-image.next-image", locale));
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
