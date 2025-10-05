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

        // 1 char -> prefix/substring search (ILIKE, case-insensitive)
//        if (q.length() == 1) {
//            return products.searchPrefixIgnoreCase(q, props.maxResults())
//                    .doOnSubscribe(s ->
//                            log.debug("lookup (ILIKE) term='{}' limit={}", q, props.maxResults()));
//        }

        // Top-N ranked results via PostgreSQL FTS (GIN index over tsvector).
        return products.searchTop(q, props.maxResults())
                .doOnSubscribe(s -> log.debug("lookup term='{}' limit={}", q, props.maxResults()))
                .doOnNext(r -> log.debug("result id={} title='{}' score={}", r.id(), r.title(), r.score()))
                .doOnError(e -> log.warn("lookup failed term='{}': {}", q, e.toString()))
                .doOnComplete(() -> log.debug("lookup complete term='{}'", q));
    }

    private static String lastPrefix(String q) {
        if (q == null) return null;
        String s = q.trim();
        if (s.isEmpty()) return null;
        // last “word-ish” token (letters/digits)
        String[] parts = s.split("\\s+");
        String last = parts[parts.length - 1].replaceAll("[^\\p{Alnum}]", "");
        return last.isEmpty() ? null : (last + ":*");
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
