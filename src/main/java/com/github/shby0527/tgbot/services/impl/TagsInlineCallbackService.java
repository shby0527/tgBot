package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.constants.RedisKeyConstant;
import com.github.shby0527.tgbot.dao.*;
import com.github.shby0527.tgbot.entities.ImgLinks;
import com.github.shby0527.tgbot.entities.InfoTags;
import com.github.shby0527.tgbot.entities.TgUploaded;
import com.github.shby0527.tgbot.entities.Userinfo;
import com.github.shby0527.tgbot.properties.Aria2Properties;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

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

    private final Collection<Long> notInTags;

    public TagsInlineCallbackService(Environment environment) {
        Binder binder = Binder.get(environment);
        Bindable<List<Long>> bindable = Bindable.listOf(Long.class);
        BindResult<List<Long>> bind = binder.bind("bot.extarn", bindable);
        notInTags = bind.orElse(Collections.emptyList());
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


    @Override
    public synchronized void process(String[] arguments, JsonNode origin) {
        JsonNode rpOrigin = JSONUtils.readJsonObject(origin, "callback_query.message.reply_to_message", JsonNode.class);
        Long fromUser = JSONUtils.readJsonObject(origin, "callback_query.from.id", Long.class);
        Long rpFromUser = JSONUtils.readJsonObject(rpOrigin, "from.id", Long.class);
        if (!fromUser.equals(rpFromUser)) {
            log.debug("not the origin user, ignored the operate");
            return;
        }
        JsonNode from = JSONUtils.readJsonObject(origin, "callback_query.message.from", JsonNode.class);
        Locale locale = getUserLocal(from);
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
        JsonNode rep = editMessage(messageSource.getMessage("replay.next-image.downloading", null, "replay.next-image.downloading", locale), origin);
        Map<String, JsonNode> chat = new HashMap<>();
        chat.put("message", rpOrigin);
        JsonNode jsonNode = JSONUtils.OBJECT_MAPPER.valueToTree(chat);
        // 这里发送websocket的消息，下载图片
        Map<String, Object> saveStatus = new HashMap<>(2);
        saveStatus.put("service", "chatRandomCallbackService");
        saveStatus.put("language", locale.toLanguageTag());
        saveStatus.put("image", links);
        saveStatus.put("chat", jsonNode);
        saveStatus.put("replay", rep);
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
            editMessage(messageSource.getMessage("replay.exception", null, "replay.exception", locale), origin);
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
        Mono<JsonNode> mono = Mono.create(sink -> {
            try {
                String json = JSONUtils.OBJECT_MAPPER.writeValueAsString(post);
                log.debug("post data: {}", json);
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
                .checkpoint()
                .blockOptional()
                .orElse(null);
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
                tags.stream().limit(5)
                        .map(InfoTags::getTag)
                        .collect(Collectors.joining(" , #", "#", ""))));
        post.put("document", uploaded.getTgid());
        String url = telegramBotProperties.getUrl() + "sendDocument";
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
}
