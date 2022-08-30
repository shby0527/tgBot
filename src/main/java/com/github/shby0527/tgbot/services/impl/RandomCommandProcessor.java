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
import org.apache.commons.lang3.RandomUtils;
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
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service("randomCommandProcessor")
public class RandomCommandProcessor implements RegisterBotCommandService {

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

    private final Collection<Long> notInTags;

    public RandomCommandProcessor(Environment environment) {
        Binder binder = Binder.get(environment);
        Bindable<List<Long>> bindable = Bindable.listOf(Long.class);
        BindResult<List<Long>> bind = binder.bind("bot.extarn", bindable);
        notInTags = bind.orElse(Collections.emptyList());
    }

    @Override
    public synchronized void process(String[] arguments, JsonNode node) {
        ImgLinks imgLinks = null;
        if (arguments.length == 0) {
            ImgLinks links = imgLinksMapper.getLatestImage();
            long id = RandomUtils.nextLong(1, links.getId());
            imgLinks = imgLinksMapper.getNearIdImage(id);
        } else {
            Collection<Long> tags = infoTagsMapper.selectTagsToId(arguments[0]);
            if (!tags.isEmpty()) {
                List<Long> imageIds = tagToImgMapper.tagsIdToImageId(tags, notInTags);
                if (!imageIds.isEmpty()) {
                    Collections.shuffle(imageIds);
                    imgLinks = imgLinksMapper.selectByPrimaryKey(imageIds.get(0));
                }
            }
        }
        JsonNode from = JSONUtils.readJsonObject(node, "message.from", JsonNode.class);
        Locale locale = getUserLocal(from);
        if (imgLinks == null) {
            sendText(messageSource.getMessage("replay.random.not-found", null, "replay.random.not-found", locale), node);
            return;
        }
        TgUploaded uploaded = tgUploadedMapper.selectByPrimaryKey(imgLinks.getId());
        if (uploaded != null) {
            sendDocument(imgLinks, uploaded, node);
            return;
        }
        String key = RedisKeyConstant.getWaitingDownloadImage(imgLinks.getId());
        if (Optional.ofNullable(redisTemplate.hasKey(key)).orElse(false)) {
            return;
        }
        JsonNode rep = sendText(messageSource.getMessage("replay.random.downloading", null, "replay.random.downloading", locale), node);
        // 这里发送websocket的消息，下载图片
        Map<String, Object> saveStatus = new HashMap<>(2);
        saveStatus.put("service", "chatRandomCallbackService");
        saveStatus.put("image", imgLinks);
        saveStatus.put("language", locale.toLanguageTag());
        saveStatus.put("chat", node);
        saveStatus.put("replay", rep);
        ValueOperations<String, Object> ops = redisTemplate.opsForValue();
        ops.set(key, saveStatus);
        // 通过websocket 开始下载
        Map<String, Object> addUrl = new HashMap<>();
        Map<String, Object> params = new HashMap<>();
        int latestIndex = imgLinks.getLink().lastIndexOf('.');
        String ext = imgLinks.getLink().substring(latestIndex);
        params.put("out", imgLinks.getId().toString() + ext);
        addUrl.put("jsonrpc", "2.0");
        addUrl.put("id", "addrpc-" + imgLinks.getId());
        addUrl.put("method", "aria2.addUri");
        addUrl.put("params", new Object[]{
                "token:" + aria2Properties.getToken(),
                new String[]{imgLinks.getLink()},
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
            sendText(messageSource.getMessage("replay.random.fail", null, "replay.random.fail", locale), node);
        }
    }

    private void sendDocument(ImgLinks links, TgUploaded uploaded, JsonNode origin) {
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
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            });
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }


    private JsonNode sendText(String text, JsonNode origin) {
        Map<String, Object> post = new HashMap<>();
        JsonNode chat = JSONUtils.readJsonObject(origin, "message.chat", JsonNode.class);
        JsonNode from = JSONUtils.readJsonObject(origin, "message.from", JsonNode.class);
        Long messageId = JSONUtils.readJsonObject(origin, "message.message_id", Long.class);
        post.put("reply_to_message_id", messageId);
        post.put("text", text + "\n @" + Optional.ofNullable(from.get("username")).map(JsonNode::textValue).orElse(""));
        post.put("chat_id", chat.get("id").longValue());
        String url = botProperties.getUrl() + "sendMessage";
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
                .blockOptional(Duration.ofMinutes(5))
                .orElse(null);
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
