package com.github.shby0527.tgbot.services.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.github.shby0527.tgbot.constants.RedisKeyConstant;
import com.github.shby0527.tgbot.dao.*;
import com.github.shby0527.tgbot.entities.*;
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
import java.util.stream.Collectors;

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
    private UserInfoMapper userInfoMapper;

    @Autowired
    private IHttpService httpService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    private List<Long> searchForTags(String keyword) {
        try {
            SearchResponse<ImageTags> search = elasticsearchClient.search(SearchRequest.of(builder ->
                    builder
                            .index("imagesearch-*")
                            .query(query ->
                                    query.match(match ->
                                            match.field("tags").query(keyword)))
                            .size(500)
            ), ImageTags.class);
            Double maxScore = Optional.ofNullable(search.hits())
                    .map(HitsMetadata::maxScore)
                    .orElse(0D);
            return Optional.ofNullable(search.hits())
                    .map(HitsMetadata::hits)
                    .orElse(Collections.emptyList())
                    .stream()
                    .filter(p -> Optional.ofNullable(p).map(Hit::score).orElse(0D) >= maxScore - 20D)
                    .filter(Objects::nonNull)
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .map(ImageTags::getImageid)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }


    @Override
    @Async("statsExecutor")
    public void scheduler(Long userid, Long chatId, String arguments) {
        if (StringUtils.isEmpty(arguments)) return;
        Collection<Long> images = searchForTags(arguments);
        if (images.isEmpty()) {
            return;
        }
        List<Long> imageIds = new ArrayList<>(images);
        Collections.shuffle(imageIds);
        ImgLinks links = imgLinksMapper.selectByPrimaryKey(imageIds.get(0));
        TgUploaded tgUploaded = tgUploadedMapper.selectByPrimaryKey(links.getId());
        Userinfo userinfo = userInfoMapper.selectByPrimaryKey(userid);
        if (tgUploaded != null) {
            sendDocument(tgUploaded, chatId);
            return;
        }
        Map<String, Object> saveStatus = new HashMap<>(6);
        saveStatus.put("service", "scheduledCallbackService");
        saveStatus.put("image", links);
        saveStatus.put("language", userinfo.getLanguageCode());
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
