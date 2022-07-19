package com.github.shby0527.tgbot.controllers;


import com.fasterxml.jackson.databind.JsonNode;
import com.github.shby0527.tgbot.services.TelegramBotProcessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/entry")
public class TelegramEntryController {

    @Autowired
    private TelegramBotProcessService telegramBotProcessService;

    @PostMapping("rec")
    public String entry(@RequestBody JsonNode json) {
        return telegramBotProcessService.process(json);
    }
}
