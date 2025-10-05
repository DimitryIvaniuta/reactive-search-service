package com.github.dimitryivaniuta.gateway.search.service;


import com.github.dimitryivaniuta.gateway.search.api.dto.SearchResult;
import com.github.dimitryivaniuta.gateway.search.repo.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class DbSearchService implements SearchService {
    private final ProductRepository products;
    private final RedisSearchService pubsub;

    @Override public Flux<String> userQueryStream(String userId) { return pubsub.userQueryStream(userId); }
    @Override public void submitQuery(String userId, String query) { pubsub.submitQuery(userId, query); }

    @Override
    public Flux<SearchResult> lookup(String term) {
        if (term == null || term.isBlank()) return Flux.empty();
        // use PostgreSQL FTS (GIN index) for low latency
        return products.searchResults(term, 20);
    }
}
