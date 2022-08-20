package com.github.shby0527.tgbot.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Data
@Configuration
@ConfigurationProperties(prefix = Aria2Properties.PREFIX)
public class Aria2Properties {

    public static final String PREFIX = "bot.aria2";

    private URI address;

    private String http;

    private String token;
}
