package com.github.shby0527.tgbot;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.github.shby0527.tgbot.entities.ImageTags;
import com.github.shby0527.tgbot.entities.InfoTags;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
public class EsTest {


    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Test
    public void esTest() {
        try {
            SearchResponse<ImageTags> search = elasticsearchClient.search(SearchRequest.of(builder ->
                    builder
                            .index("imagesearch-py")
                            .query(query ->
                                    query.match(match ->
                                            match.field("tags").query("yuzusoft game cg")))
                            .size(500)
            ), ImageTags.class);
            Double maxScore = Optional.ofNullable(search.hits())
                    .map(HitsMetadata::maxScore)
                    .orElse(0D);
            log.info("{}", Optional.ofNullable(search.hits())
                    .map(HitsMetadata::hits)
                    .orElse(Collections.emptyList())
                    .stream()
                    .filter(p -> Optional.ofNullable(p).map(Hit::score).orElse(0D) >= maxScore - 20D)
                    .filter(Objects::nonNull)
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()));
        } catch (IOException e) {
            log.error("e", e);
        }
    }
}
