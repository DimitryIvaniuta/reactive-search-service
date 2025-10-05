package com.github.dimitryivaniuta.gateway.search.service;

import com.github.dimitryivaniuta.gateway.search.api.dto.SearchResult;
import com.github.dimitryivaniuta.gateway.search.config.SearchProps;
import com.github.dimitryivaniuta.gateway.search.repo.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
@Slf4j
public class DbSearchService implements SearchService {

    private final ProductRepository products;
    private final RedisSearchService pubsub;   // cluster-safe keystroke transport
    private final SearchProps props;           // maxResults, debounceWindow, etc.

    @Override
    public Flux<String> userQueryStream(String userId) {
        return pubsub.userQueryStream(requireUser(userId));
    }

    @Override
    public void submitQuery(String userId, String query) {
        pubsub.submitQuery(requireUser(userId), query);
    }

    @Override
    public Flux<SearchResult> lookup(String term) {
        final String q = normalize(term);
        if (q.isEmpty()) return Flux.empty();
        // Top-N ranked results via PostgreSQL FTS (GIN index over tsvector).
        return products.searchTop(q, props.maxResults());
    }

    private static String requireUser(String userId) {
        if (userId == null || userId.isBlank())
            throw new IllegalArgumentException("userId must be provided");
        return userId;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim();
    }
}
