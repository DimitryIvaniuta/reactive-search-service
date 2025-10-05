package com.github.dimitryivaniuta.gateway.search.repo;

import com.github.dimitryivaniuta.gateway.search.api.dto.SearchResult;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Reactive PostgreSQL full-text search using websearch_to_tsquery.
 *
 * - Uses a CTE to bind the tsquery once.
 * - Orders by ts_rank (desc) and a stable tiebreaker (id).
 * - Defensive guards for blank queries (emit empty).
 */
@Repository
@RequiredArgsConstructor
class ProductSearchRepositoryImpl implements ProductSearchRepository {

    private static final String CFG = "english";

    private final DatabaseClient client;

    @Override
    public Flux<SearchResult> searchTop(String term, int limit) {
        String q = normalize(term);
        if (q.isEmpty() || limit <= 0) return Flux.empty();
        return doSearchRanked(q, limit, 0);
    }

    @Override
    public Mono<Page<SearchResult>> searchPage(String term, Pageable pageable) {
        String q = normalize(term);
        if (q.isEmpty()) return Mono.just(Page.empty(pageable));

        int size = Math.max(1, pageable.getPageSize());
        long offset = Math.max(0, pageable.getOffset());

        Mono<Long> count = countMatches(q);
        Flux<SearchResult> data = doSearchRanked(q, size, offset);

        return Mono.zip(count, data.collectList())
                .map(tuple -> new PageImpl<>(tuple.getT2(), pageable, tuple.getT1()));
    }

    private Flux<SearchResult> doSearchRanked(String term, int limit, long offset) {
        // CTE binds the tsquery once; stable order by rank desc, then id asc.
        final String sql = """
                  with q as (select websearch_to_tsquery(:cfg, :term) as query)
                  select p.id, p.title, ts_rank(p.tsv, q.query) as score
                    from products p, q
                   where p.tsv @@ q.query
                order by score desc, p.id asc
                   limit :limit
                  offset :offset
                """;

        return client.sql(sql)
                .bind("cfg", CFG)
                .bind("term", term)
                .bind("limit", limit)
                .bind("offset", offset)
                .map(this::mapRowToSearchResult)
                .all();
    }

    private Mono<Long> countMatches(String term) {
        final String sql = """
                with q as (select websearch_to_tsquery(:cfg, :term) as query)
                select count(*) as cnt
                  from products p, q
                 where p.tsv @@ q.query
                """;

        return client.sql(sql)
                .bind("cfg", CFG)
                .bind("term", term)
                .map((row, md) -> getRequired(row, "cnt", Number.class).longValue())
                .one();
    }

    private SearchResult mapRowToSearchResult(Row row, RowMetadata md) {
        Long id = getRequired(row, "id", Long.class);
        String title = getRequired(row, "title", String.class);
        // score can be null in pathological cases; default to 0.0
        Double score = getOptional(row, "score", Double.class);
        double s = score == null ? 0.0 : score.doubleValue();
        return new SearchResult(id, title, s);
    }

    private static <T> T getRequired(Row row, String column, Class<T> type) {
        T v = row.get(column, type);
        if (v == null) {
            throw new DataAccessResourceFailureException("Missing column '%s'".formatted(column));
        }
        return v;
    }

    private static <T> T getOptional(Row row, String column, Class<T> type) {
        return row.get(column, type);
    }

    private static String normalize(String term) {
        return Objects.toString(term, "").trim();
    }
}
