package com.github.shby0527.tgbot.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = CommandRegisterProperties.PREFIX)
public class CommandRegisterProperties {

    public static final String PREFIX = "bot.command";

    private Map<String, String> commands;
}
