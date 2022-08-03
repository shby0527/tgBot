package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.constants.RedisKeyConstant;
import com.github.shby0527.tgbot.dao.ImgLinksMapper;
import com.github.shby0527.tgbot.dao.InfoTagsMapper;
import com.github.shby0527.tgbot.dao.TagToImgMapper;
import com.github.shby0527.tgbot.dao.TgUploadedMapper;
import com.github.shby0527.tgbot.entities.ImgLinks;
import com.github.shby0527.tgbot.entities.InfoTags;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
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
@Service("tagsCallbackProcess")
public class TagsInlineCallbackService implements InlineCallbackService {

    @Autowired
    private TagToImgMapper tagToImgMapper;

    @Autowired
    private ImgLinksMapper imgLinksMapper;

    @Autowired
    private TgUploadedMapper tgUploadedMapper;

    @Autowired
    private InfoTagsMapper infoTagsMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private IHttpService httpService;

    @Autowired
    private TelegramBotProperties telegramBotProperties;

    @Autowired
    private Aria2Properties aria2Properties;

    private final Collection<Long> notInTags;

    public TagsInlineCallbackService(Environment environment) {
        Binder binder = Binder.get(environment);
        Bindable<List<Long>> bindable = Bindable.listOf(Long.class);
        BindResult<List<Long>> bind = binder.bind("bot.extarn", bindable);
        notInTags = bind.orElse(Collections.emptyList());
    }


    @Override
    public void process(String[] arguments, JsonNode origin) {
        JsonNode rpOrigin = JSONUtils.readJsonObject(origin, "callback_query.message.reply_to_message", JsonNode.class);
        Long fromUser = JSONUtils.readJsonObject(origin, "callback_query.from.id", Long.class);
        Long rpFromUser = JSONUtils.readJsonObject(rpOrigin, "from.id", Long.class);
        if (!fromUser.equals(rpFromUser)) {
            log.debug("not the origin user, ignored the operate");
            return;
        }
        Long tagsId = Long.valueOf(arguments[0]);
        List<Long> imageId = tagToImgMapper.tagsIdToImageId(Collections.singleton(tagsId), notInTags);
        Collections.shuffle(imageId);
        ImgLinks links = imgLinksMapper.selectByPrimaryKey(imageId.get(0));
        TgUploaded tgUploaded = tgUploadedMapper.selectByPrimaryKey(links.getId());
        if (tgUploaded != null) {
            deleteMessage(origin);
            sendDocument(links, tgUploaded, rpOrigin);
            return;
        }
        String key = RedisKeyConstant.getWaitingDownloadImage(links.getId());
        if (Optional.ofNullable(redisTemplate.hasKey(key)).orElse(false)) {
            return;
        }
        JsonNode rep = editMessage("今、ダウロード中、しばらくお待ち下さい", origin);
        Map<String, JsonNode> chat = new HashMap<>();
        chat.put("message", rpOrigin);
        JsonNode jsonNode = JSONUtils.OBJECT_MAPPER.valueToTree(chat);
        // 这里发送websocket的消息，下载图片
        Map<String, Object> saveStatus = new HashMap<>(2);
        saveStatus.put("service", "chatRandomCallbackService");
        saveStatus.put("image", links);
        saveStatus.put("chat", jsonNode);
        saveStatus.put("replay", rep);
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
            editMessage("ご主人さまの探しものがなくなっちゃった、うぅぅぅぅQVQ", origin);
        }
    }


    private JsonNode editMessage(String text, JsonNode origin) {
        Map<String, Object> post = new HashMap<>();
        Long chatId = JSONUtils.readJsonObject(origin, "callback_query.message.chat.id", Long.class);
        Long messageId = JSONUtils.readJsonObject(origin, "callback_query.message.message_id", Long.class);
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

    private void sendDocument(ImgLinks links, TgUploaded uploaded, JsonNode origin) {
        List<InfoTags> tags = tagToImgMapper.getImagesTags(links.getId());
        Map<String, Object> post = new HashMap<>();
        JsonNode chat = origin.get("chat");
        JsonNode from = origin.get("from");
        Long messageId = origin.get("message_id").longValue();
        post.put("reply_to_message_id", messageId);
        post.put("chat_id", chat.get("id").longValue());
        post.put("caption", MessageFormat.format("@{0} \nAuthor: {1} \n{2}x{3} \n tags: {4}",
                Optional.ofNullable(from.get("username")).map(JsonNode::textValue).orElse(""),
                Optional.ofNullable(links.getAuthor()).orElse("无"),
                Optional.ofNullable(links.getWidth()).orElse(0),
                Optional.ofNullable(links.getHeight()).orElse(0),
                tags.stream().limit(5).map(InfoTags::getTag).collect(Collectors.joining(" , "))));
        post.put("document", uploaded.getTgid());
        String url = telegramBotProperties.getUrl() + "sendDocument";
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
    }

    public void deleteMessage(JsonNode origin) {
        Map<String, Object> post = new HashMap<>();
        Long chatId = JSONUtils.readJsonObject(origin, "callback_query.message.chat.id", Long.class);
        post.put("chat_id", chatId);
        Long messageId = JSONUtils.readJsonObject(origin, "callback_query.message.message_id", Long.class);
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
