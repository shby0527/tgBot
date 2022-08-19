package com.github.shby0527.tgbot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.test.context.ActiveProfiles;

import java.util.Locale;

@ActiveProfiles("test")
@SpringBootTest
public class ScheduleTest {

    @Autowired
    private MessageSource messageSource;

    @Test
    public void messageTest() {
        Locale locale = Locale.forLanguageTag("ja-jp");
        System.out.println(messageSource.getMessage("replay.chat-info.replay", new Object[]{1,2,3,4,5}, "command.search.description", locale));
    }

}
