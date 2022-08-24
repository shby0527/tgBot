package com.github.shby0527.tgbot.services.impl;

import com.github.shby0527.tgbot.constants.RedisKeyConstant;
import com.github.shby0527.tgbot.dao.ImgLinksMapper;
import com.github.shby0527.tgbot.dao.InfoTagsMapper;
import com.github.shby0527.tgbot.dao.TagToImgMapper;
import com.github.shby0527.tgbot.dao.TgUploadedMapper;
import com.github.shby0527.tgbot.entities.ImgLinks;
import com.github.shby0527.tgbot.entities.TgUploaded;
import com.github.shby0527.tgbot.properties.Aria2Properties;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.SchedulerService;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Slf4j
@Service("autoSendSchedulerService")
public class AutoSendSchedulerService implements SchedulerService {

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


    @Override
    @Async("statsExecutor")
    public void scheduler(Long chatId, String arguments) {
        if (StringUtils.isEmpty(arguments)) return;
        Collection<Long> tags = infoTagsMapper.selectTagsToId(arguments);
        List<Long> imageIds = tagToImgMapper.tagsIdToImageId(tags, null);
        Collections.shuffle(imageIds);
        ImgLinks links = imgLinksMapper.selectByPrimaryKey(imageIds.get(0));
        TgUploaded tgUploaded = tgUploadedMapper.selectByPrimaryKey(links.getId());
        if (tgUploaded != null) {
            sendDocument(tgUploaded, chatId);
            return;
        }
        Map<String, Object> saveStatus = new HashMap<>(2);
        saveStatus.put("service", "scheduledCallbackService");
        saveStatus.put("image", links);
        saveStatus.put("scc", chatId);
        String key = RedisKeyConstant.getWaitingDownloadImage(links.getId());
        ValueOperations<String, Object> ops = redisTemplate.opsForValue();
        if (Optional.ofNullable(redisTemplate.hasKey(key)).orElse(false)) return;
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
        }

    }


    private void sendDocument(TgUploaded uploaded, Long chatId) {
        Map<String, Object> post = new HashMap<>();
        post.put("chat_id", chatId);
        post.put("document", uploaded.getTgid());
        String url = properties.getUrl() + "sendDocument";
        try {
            String json = JSONUtils.OBJECT_MAPPER.writeValueAsString(post);
            log.debug("post data: {}", json);
            httpService.postForString(url, null, null, json, MediaType.APPLICATION_JSON_VALUE, httpResponse -> {
                try (httpResponse) {
                    String back = httpResponse.getContent();
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
