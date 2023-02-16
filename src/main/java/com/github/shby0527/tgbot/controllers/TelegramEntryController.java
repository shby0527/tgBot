package com.github.shby0527.tgbot.controllers;


import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.services.TelegramBotProcessService;
import com.github.shby0527.tgbot.services.WebHookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/entry")
public class TelegramEntryController {

    @Autowired
    private TelegramBotProcessService telegramBotProcessService;

    @Autowired
    private ObjectProvider<WebHookService> webHookServices;

    @PostMapping("rec")
    public String entry(@RequestBody JsonNode json) {
        telegramBotProcessService.process(json);
        return "True";
    }

    @PostMapping("webhook")
    public String webhook(@RequestParam("body") String body, @RequestParam("target") String target) {
        webHookServices.forEach(s -> s.handler(body, target));

        return "OK";
    }
}
