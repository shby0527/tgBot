package com.github.shby0527.tgbot.dao;

import com.github.shby0527.tgbot.entities.ImgLinks;

public interface ImgLinksMapper {
    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table img_links
     *
     * @mbg.generated Tue Jul 19 12:04:09 CST 2022
     */
    int insert(ImgLinks record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table img_links
     *
     * @mbg.generated Tue Jul 19 12:04:09 CST 2022
     */
    int insertSelective(ImgLinks record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table img_links
     *
     * @mbg.generated Tue Jul 19 12:04:09 CST 2022
     */
    ImgLinks selectByPrimaryKey(Long id);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table img_links
     *
     * @mbg.generated Tue Jul 19 12:04:09 CST 2022
     */
    int updateByPrimaryKeySelective(ImgLinks record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table img_links
     *
     * @mbg.generated Tue Jul 19 12:04:09 CST 2022
     */
    int updateByPrimaryKey(ImgLinks record);
}