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
import com.github.shby0527.tgbot.services.RegisterBotCommandService;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service("sendImageForIdCommandProcessor")
public class SendImageForIdCommandProcessor implements RegisterBotCommandService {

    @Autowired
    private IHttpService httpService;

    @Autowired
    private TelegramBotProperties botProperties;

    @Autowired
    private Aria2Properties aria2Properties;

    @Autowired
    private ImgLinksMapper imgLinksMapper;

    @Autowired
    private InfoTagsMapper infoTagsMapper;

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
    public void process(String[] arguments, JsonNode node) {
        JsonNode from = JSONUtils.readJsonObject(node, "message.from", JsonNode.class);
        String type = JSONUtils.readJsonObject(node, "message.chat.type", String.class);
        Locale local = getUserLocal(from);
        if (arguments == null || arguments.length <= 0) {
            String noArgument = messageSource.getMessage("replay.image-id.no-argument", null, "replay.image-id.no-argument", local);
            sendText(noArgument, node, null);
            return;
        }
        try {
            long id = Long.parseLong(arguments[0]);
            long end = 0L;
            if (arguments.length > 1 && NumberUtils.isCreatable(arguments[1]) && "private".equals(type)) {
                end = Long.parseLong(arguments[1]);
            }
            long i = 0;
            do {
                ImgLinks links = imgLinksMapper.selectByPrimaryKey(id + i);
                if (links == null) {
                    String notFound = messageSource.getMessage("replay.image-id.not-found", null, "replay.image-id.not-found", local);
                    sendText(notFound, node, null);
                    i++;
                    continue;
                }
                TgUploaded uploaded = tgUploadedMapper.selectByPrimaryKey(links.getId());
                if (uploaded != null) {
                    sendDocument(links, uploaded, node, back -> log.debug("finished {}", back));
                    i++;
                    continue;
                }
                String key = RedisKeyConstant.getWaitingDownloadImage(links.getId());
                if (Optional.ofNullable(redisTemplate.hasKey(key)).orElse(false)) {
                    i++;
                    continue;
                }
                String downloadingMessage = messageSource.getMessage("replay.random.downloading", null, "replay.random.downloading", local);
                sendText(downloadingMessage, node, back -> {
                    Map<String, Object> saveStatus = new HashMap<>(2);
                    saveStatus.put("service", "chatRandomCallbackService");
                    saveStatus.put("image", links);
                    saveStatus.put("language", local.toLanguageTag());
                    saveStatus.put("chat", node);
                    saveStatus.put("replay", back);
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
                        String fail = messageSource.getMessage("replay.random.fail", null, "replay.random.fail", local);
                        sendText(fail, node, null);
                    }
                });
                i++;
            } while (end > 0 && end >= id + i && i < 100L);
        } catch (Throwable t) {
            String exception = messageSource.getMessage("replay.exception", null, "replay.exception", local);
            sendText(exception, node, null);
        }

    }


    private void sendText(String text, JsonNode origin, Consumer<JsonNode> callback) {
        Map<String, Object> post = new HashMap<>();
        JsonNode chat = JSONUtils.readJsonObject(origin, "message.chat", JsonNode.class);
        JsonNode from = JSONUtils.readJsonObject(origin, "message.from", JsonNode.class);
        Long messageId = JSONUtils.readJsonObject(origin, "message.message_id", Long.class);
        post.put("reply_to_message_id", messageId);
        post.put("text", text + "\n @" + Optional.ofNullable(from.get("username")).map(JsonNode::textValue).orElse(""));
        post.put("chat_id", chat.get("id").longValue());
        String url = botProperties.getUrl() + "sendMessage";
        try {
            String json = JSONUtils.OBJECT_MAPPER.writeValueAsString(post);
            log.debug("post data: {}", json);
            httpService.postForString(url, null, null, json, MediaType.APPLICATION_JSON_VALUE, httpResponse -> {
                try (httpResponse) {
                    JsonNode back = httpResponse.getJson();
                    log.debug("return back {}", back);
                    if (callback != null) {
                        callback.accept(back);
                    }
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            });
        } catch (IOException e) {
            log.error("IO 失败", e);
        }
    }


    private void sendDocument(ImgLinks links, TgUploaded uploaded, JsonNode origin, Consumer<JsonNode> callback) {
        List<InfoTags> tags = tagToImgMapper.getImagesTags(links.getId());
        Map<String, Object> post = new HashMap<>();
        JsonNode chat = JSONUtils.readJsonObject(origin, "message.chat", JsonNode.class);
        JsonNode from = JSONUtils.readJsonObject(origin, "message.from", JsonNode.class);
        Long messageId = JSONUtils.readJsonObject(origin, "message.message_id", Long.class);
        post.put("reply_to_message_id", messageId);
        post.put("chat_id", chat.get("id").longValue());
        post.put("caption", MessageFormat.format("@{0} \nAuthor: {1} \n{2}x{3} \ntags: {4}",
                Optional.ofNullable(from.get("username")).map(JsonNode::textValue).orElse(""),
                Optional.ofNullable(links.getAuthor()).orElse("无"),
                Optional.ofNullable(links.getWidth()).orElse(0),
                Optional.ofNullable(links.getHeight()).orElse(0),
                tags.stream().limit(5)
                        .map(InfoTags::getTag)
                        .collect(Collectors.joining(" , #", "#", ""))));
        post.put("document", uploaded.getTgid());
        String url = botProperties.getUrl() + "sendDocument";
        try {
            String json = JSONUtils.OBJECT_MAPPER.writeValueAsString(post);
            log.debug("post data: {}", json);
            httpService.postForString(url, null, null, json, MediaType.APPLICATION_JSON_VALUE, httpResponse -> {
                try (httpResponse) {
                    JsonNode back = httpResponse.getJson();
                    log.debug("return back {}", back);
                    if (callback != null) {
                        callback.accept(back);
                    }
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            });
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
