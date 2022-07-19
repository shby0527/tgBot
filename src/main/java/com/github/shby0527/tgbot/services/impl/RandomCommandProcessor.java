package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.dao.ImgLinksMapper;
import com.github.shby0527.tgbot.dao.InfoTagsMapper;
import com.github.shby0527.tgbot.dao.TagToImgMapper;
import com.github.shby0527.tgbot.dao.TgUploadedMapper;
import com.github.shby0527.tgbot.entities.ImgLinks;
import com.github.shby0527.tgbot.properties.TelegramBotProperties;
import com.github.shby0527.tgbot.services.RegisterBotCommandService;
import com.xw.task.services.HttpResponse;
import com.xw.task.services.IHttpService;
import com.xw.web.utils.JSONUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Slf4j
@Service("randomCommandProcessor")
public class RandomCommandProcessor implements RegisterBotCommandService {

    @Autowired
    private IHttpService httpService;

    @Autowired
    private TelegramBotProperties botProperties;

    @Autowired
    private ImgLinksMapper imgLinksMapper;

    @Autowired
    private InfoTagsMapper infoTagsMapper;

    @Autowired
    private TagToImgMapper tagToImgMapper;

    @Autowired
    private TgUploadedMapper tgUploadedMapper;

    @Override
    public void process(String[] arguments, JsonNode node) {
        ImgLinks imgLinks = null;
        if (arguments.length == 0) {
            ImgLinks links = imgLinksMapper.getLatestImage();
            long id = RandomUtils.nextLong(1, links.getId());
            imgLinks = imgLinksMapper.getNearIdImage(id);
        } else {
            Collection<Long> tags = infoTagsMapper.selectTagsToId(arguments[0]);
            if (!tags.isEmpty()) {
                List<Long> imageIds = tagToImgMapper.tagsIdToImageId(tags);
                Collections.shuffle(imageIds);
                imgLinks = imgLinksMapper.selectByPrimaryKey(imageIds.get(0));
            }
        }
        if (imgLinks == null) {
            sendText("ご主人さまのさかしものがなくなっちゃった、うぅぅぅぅQVQ", node);
            return;
        }

    }


    private void sendText(String text, JsonNode origin) {
        Map<String, Object> post = new HashMap<>();
        JsonNode chat = JSONUtils.readJsonObject(origin, "message.chat", JsonNode.class);
        JsonNode from = JSONUtils.readJsonObject(origin, "message.from", JsonNode.class);
        Long messageId = JSONUtils.readJsonObject(origin, "message.message_id", Long.class);
        post.put("reply_to_message_id", messageId);
        post.put("text", text + "\n @" + from.get("username").textValue());
        post.put("chat_id", chat.get("id").longValue());
        String url = "https://api.telegram.org/bot" + botProperties.getToken() + "/sendMessage";
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
