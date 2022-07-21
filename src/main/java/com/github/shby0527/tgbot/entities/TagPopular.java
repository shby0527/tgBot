package com.github.shby0527.tgbot.entities;

import lombok.Data;

@Data
public class TagPopular {

    private Long tagId;

    private String tag;

    private Long count;
}
