package com.github.shby0527.tgbot.services.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.services.OssResourceLoader;
import com.xw.task.services.HttpResponse;
import com.xw.task.services.IHttpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OssResourceLoaderImpl implements OssResourceLoader {

    @Autowired
    private IHttpService httpService;

    @Value("${bot.unregister-command-url}")
    private String actionLink;

    @Override
    @Cacheable(cacheNames = "ugAction", key = "config")
    public JsonNode readForUnRegisterAction() {
        try (HttpResponse response = httpService.get(actionLink, null, null, null)) {
            return response.getJson();
        } catch (Throwable t) {
            log.error("visited fail", t);
            return null;
        }
    }
}
