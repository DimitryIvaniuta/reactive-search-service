package com.github.dimitryivaniuta.gateway.search.repo;

import com.github.dimitryivaniuta.gateway.search.api.dto.SearchResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Custom FTS API (reactive) backed by PostgreSQL.
 */
public interface ProductSearchRepository {

    /**
     * Ranked full-text search; returns top-N results.
     *
     * @param term  user query (natural language, same semantics as web search)
     * @param limit max results to emit (e.g., 20)
     */
    Flux<SearchResult> searchTop(String term, int limit);

    /**
     * Ranked full-text search with paging.
     *
     * NOTE: Paging in reactive land requires a separate count query.
     */
    Mono<Page<SearchResult>> searchPage(String term, Pageable pageable);
}
