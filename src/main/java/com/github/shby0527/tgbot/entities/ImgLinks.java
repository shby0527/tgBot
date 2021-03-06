package com.github.shby0527.tgbot.entities;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 表名: img_links
 * 创建时间: 2022-07-19 12:04:09
 */
@Data
public class ImgLinks implements Serializable {
    /**
     * Table:     img_links
     * Column:    id
     * Nullable:  false
     */
    private Long id;

    /**
     * Table:     img_links
     * Column:    sha256
     * Nullable:  false
     */
    private String sha256;

    /**
     * Table:     img_links
     * Column:    link
     * Nullable:  false
     */
    private String link;

    /**
     * Table:     img_links
     * Column:    ctime
     * Nullable:  true
     */
    private Date ctime;

    /**
     * Table:     img_links
     * Column:    mtime
     * Nullable:  true
     */
    private Date mtime;

    /**
     * 0 not downloaded ,1 downloaded
     *
     * Table:     img_links
     * Column:    downloaded
     * Nullable:  true
     */
    private Byte downloaded;

    /**
     * 作者
     *
     * Table:     img_links
     * Column:    author
     * Nullable:  true
     */
    private String author;

    /**
     * 文件大小
     *
     * Table:     img_links
     * Column:    file_size
     * Nullable:  true
     */
    private Long fileSize;

    /**
     * 图片高度
     *
     * Table:     img_links
     * Column:    height
     * Nullable:  true
     */
    private Integer height;

    /**
     * 图片宽度
     *
     * Table:     img_links
     * Column:    width
     * Nullable:  true
     */
    private Integer width;

    /**
     * 分类限制级别
     *
     * Table:     img_links
     * Column:    rating
     * Nullable:  true
     */
    private String rating;

    /**
     * 图片ID
     *
     * Table:     img_links
     * Column:    imageId
     * Nullable:  true
     */
    private Long imageid;

    /**
     * This field was generated by MyBatis Generator.
     * This field corresponds to the database table img_links
     *
     * @mbg.generated Tue Jul 19 12:04:09 CST 2022
     */
    private static final long serialVersionUID = 1L;
}