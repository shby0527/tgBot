package com.github.shby0527.tgbot.constants;

import java.text.MessageFormat;

public abstract class RedisKeyConstant {

    private final static String WAITING_DOWNLOAD_IMAGE = "waiting:image:{0}";

    private final static String TAG_NEXT_PAGE_TO = "tags:pagination:{0}";

    public static String getWaitingDownloadImage(Long imageId) {
        return MessageFormat.format(WAITING_DOWNLOAD_IMAGE, imageId.toString());
    }

    public static String getTagNextPageTo(String key) {
        return MessageFormat.format(TAG_NEXT_PAGE_TO, key);
    }

    public final static String POPULAR_TAGS = "popular:tags";
}
