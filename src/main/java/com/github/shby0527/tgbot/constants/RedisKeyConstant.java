package com.github.shby0527.tgbot.constants;

import java.text.MessageFormat;

public abstract class RedisKeyConstant {

    private final static String WAITING_DOWNLOAD_IMAGE = "waiting:image:{0}";

    public static String getWaitingDownloadImage(Long imageId) {
        return MessageFormat.format(WAITING_DOWNLOAD_IMAGE, imageId.toString());
    }
}
