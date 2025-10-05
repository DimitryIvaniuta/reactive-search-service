package com.github.dimitryivaniuta.gateway.search.service;

import com.github.dimitryivaniuta.gateway.search.api.dto.SearchResult;
import reactor.core.publisher.Flux;

public interface SearchService {
    Flux<String> userQueryStream(String userId);
    void submitQuery(String userId, String query);
    Flux<SearchResult> lookup(String term);
}

