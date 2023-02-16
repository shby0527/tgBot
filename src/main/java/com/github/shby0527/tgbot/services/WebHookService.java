package com.github.shby0527.tgbot.services;

public interface WebHookService {

    String handler(String body, String target);
}
