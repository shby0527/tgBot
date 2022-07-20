package com.github.shby0527.tgbot.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = TelegramBotProperties.PREFIX)
public class TelegramBotProperties {

    public static final String PREFIX = "bot.api";

    private String token;

    private Map<String, String> serviceToProcess;

    private String defaultProcess;

    private String url;

    private AutoChatConfig[] autoChat;

    @Data
    public static class AutoChatConfig {
        private Long chatId;

        private String tag;
    }
}
