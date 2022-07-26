package com.github.shby0527.tgbot.schedules;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.constants.RedisKeyConstant;
import com.github.shby0527.tgbot.dao.ImgLinksMapper;
import com.github.shby0527.tgbot.dao.InfoTagsMapper;
import com.github.shby0527.tgbot.dao.TagToImgMapper;
import com.github.shby0527.tgbot.dao.TgUploadedMapper;
import com.github.shby0527.tgbot.entities.ImgLinks;
import com.github.shby0527.tgbot.entities.TgUploaded;
import com.github.shby0527.tgbot.properties.Aria2Properties;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.websocket.Aria2WebSocketHandler;
import com.xw.task.services.HttpResponse;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;

@Slf4j
@Service
public class AutoSchedule {

    @Autowired
    private TelegramBotProperties properties;

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
    private IHttpService httpService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final Collection<Long> notInTags;

    public AutoSchedule(Environment environment) {
        Binder binder = Binder.get(environment);
        Bindable<List<Long>> bindable = Bindable.listOf(Long.class);
        BindResult<List<Long>> bind = binder.bind("bot.extarn", bindable);
        notInTags = bind.orElse(Collections.emptyList());
    }

    @Scheduled(cron = "0 20 6 * * ?")
    public void autoSend() {
        for (TelegramBotProperties.AutoChatConfig cfg : properties.getAutoChat()) {
            Collection<Long> tags = infoTagsMapper.selectTagsToId(cfg.getTag());
            List<Long> imageIds = tagToImgMapper.tagsIdToImageId(tags, notInTags);
            Collections.shuffle(imageIds);
            ImgLinks links = imgLinksMapper.selectByPrimaryKey(imageIds.get(0));
            TgUploaded tgUploaded = tgUploadedMapper.selectByPrimaryKey(links.getId());
            if (tgUploaded != null) {
                sendDocument(links, tgUploaded, cfg.getChatId());
                continue;
            }
            Map<String, Object> saveStatus = new HashMap<>(2);
            saveStatus.put("image", links);
            saveStatus.put("scc", cfg.getChatId());
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
            }
        }
    }


    private void sendDocument(ImgLinks links, TgUploaded uploaded, Long chatId) {
        Map<String, Object> post = new HashMap<>();
        post.put("chat_id", chatId);
        post.put("document", uploaded.getTgid());
        String url = properties.getUrl() + "sendDocument";
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
}
