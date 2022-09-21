package com.github.shby0527.tgbot;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.github.shby0527.tgbot.entities.InfoTags;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
public class EsTest {


    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Test
    public void esTest() {
        try {
            SearchResponse<InfoTags> search = elasticsearchClient.search(SearchRequest.of(builder ->
                    builder.query(query ->
                                    query.match(match ->
                                            match.field("tag").query("game cg")))
                            .size(10)
            ), InfoTags.class);
            log.info("list: {}", search.hits().hits()
                    .stream()
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()));
        } catch (IOException e) {
            log.error("error", e);
        }
    }
}
