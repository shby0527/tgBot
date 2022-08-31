package com.github.shby0527.tgbot;

import com.xw.task.services.IHttpService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

@Slf4j
@SpringBootTest
public class HttpTest {

    @Autowired
    private IHttpService httpService;


    @Test
    public void DeathLock() throws Throwable {

        String url = "https://www.google.com";
        CountDownLatch latch = new CountDownLatch(1);

        httpService.get(url, null, null, httpResponse -> {
            try (httpResponse) {
                String content = httpResponse.getContent();
                final Mono<String> mono = Mono.create(sink -> {
                    try {
                        httpService.get(url, null, null, httpResponse1 -> {
                            try (httpResponse1) {
                                String content1 = httpResponse1.getContent();
                                sink.success(content1);
                            } catch (IOException e) {
                                log.error("", e);
                                sink.error(e);
                            }
                        });
                    } catch (IOException e) {
                        log.error("e", e);
                        sink.error(e);
                    }
                });
                log.info(mono.checkpoint()
                        .blockOptional()
                        .orElse("")
                        .concat(content));
            } catch (IOException e) {
                log.error("test", e);
            } finally {
                latch.countDown();
            }
        });
        latch.await();
    }
}
