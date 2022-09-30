package com.github.shby0527.tgbot;

import com.xw.web.utils.IDCardUtils;
import org.junit.jupiter.api.Test;

public class NormalTest {


    @Test
    public void normalTest() {
        String idCard = "";

        final boolean checked = IDCardUtils.checkIDCardNumber(idCard);
        if (!checked) {
            System.out.println(idCard + "身份证不合法");
        } else {
            System.out.println(idCard + "身份证合法，但是真实性未知");
        }
    }
}
