package com.github.shby0527.tgbot.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = TelegramBotProperties.PREFIX)
public class TelegramBotProperties {

    public static final String PREFIX = "bot.api";

    private String token;
}
