package com.github.dimitryivaniuta.gateway.search.repo;

import com.github.dimitryivaniuta.gateway.search.api.dto.SearchResult;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Reactive PostgreSQL full-text search using websearch_to_tsquery.
 * <p>
 * - Uses a CTE to bind the tsquery once.
 * - Orders by ts_rank (desc) and a stable tiebreaker (id).
 * - Defensive guards for blank queries (emit empty).
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class ProductSearchRepositoryImpl implements ProductSearchRepository {

    private static final String CFG = "english";

    private final R2dbcEntityTemplate template;

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

    private static String lastPrefixToken(String q) {
        if (q == null) return null;
        String s = q.trim();
        if (s.isEmpty()) return null;
        // take last whitespace-separated piece, keep only letters/digits
        String[] parts = s.split("\\s+");
        String last = parts[parts.length - 1].replaceAll("[^\\p{Alnum}]", "");
        return last.isEmpty() ? null : (last + ":*"); // to_tsquery prefix
    }

    private static List<String> tokens(String q) {
        if (q == null) return List.of();
        return Arrays.stream(q.trim().split("\\s+"))
                .map(s -> s.replaceAll("[^\\p{Alnum}]", "").toLowerCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .toList();
    }

    /**
     * Build a to_tsquery string with prefix operators for every token:
     *  ["sam","ga"] -> "sam:* & ga:*"
     * Returns null if no valid tokens.
     */
    private static String buildPrefixTsQuery(List<String> toks) {
        if (toks.isEmpty()) return null;
        // to_tsquery syntax: words must not start/end with operators; join with &
        return toks.stream()
                .map(t -> t + ":*")
                .collect(Collectors.joining(" & "));
    }

    private Flux<SearchResult> doSearchRanked(String term, int limit, long offset) {
        final DatabaseClient client = template.getDatabaseClient();

        // Build a prefix tsquery covering ALL tokens so incremental typing works:
        // "sam"         -> "sam:*"
        // "sam ga"      -> "sam:* & ga:*"
        // "sam g ala"   -> "sam:* & g:* & ala:*"
        final List<String> toks = tokens(term);
        final String tsPrefix = buildPrefixTsQuery(toks); // null if no tokens


        final String sql = """
                   with q as (
                     select
                       case
                         when :tsPrefix is not null then
                           to_tsquery(CAST(:cfg AS regconfig), CAST(:tsPrefix AS text))
                         else
                           websearch_to_tsquery(CAST(:cfg AS regconfig), CAST(:term AS text))
                       end as query
                   )
                   select p.id, p.title, p.description, ts_rank(p.tsv, q.query) as score
                     from products p, q
                    where p.tsv @@ q.query
                order by score desc, p.id asc
                   limit :limit
                  offset :offset
                """;

        return client.sql(sql)
                .bind("cfg", CFG)
                .bind("term", term)          // used only when tsPrefix is null
                .bind("tsPrefix", tsPrefix)  // e.g., "sam:* & ga:*" or null
                .bind("limit", limit)
                .bind("offset", offset)
                .map(this::mapRowToSearchResult)
                .all()
                .doOnSubscribe(s -> log.debug(
                        "SQL[ranked] cfg='{}' term='{}' tsPrefix='{}' limit={} offset={}",
                        CFG, term, tsPrefix, limit, offset))
                .doOnNext(r -> log.debug("SQL[ranked] row -> id={} title='{}' score={}",
                        r.id(), r.title(), r.score()))
                .doOnComplete(() -> log.debug("SQL[ranked] complete term='{}'", term))
                .doOnError(e -> log.warn("SQL[ranked] error term='{}': {}", term, e.toString()));
    }

    @Override
    public Flux<SearchResult> searchPrefixIgnoreCase(String term, int limit) {
        final String q = normalize(term);
        if (q.isEmpty() || limit <= 0) return Flux.empty();

        // Build %term% pattern once
        final String pattern = "%" + q + "%";

        // Rank: title match first, then earliest position in title, then id.
        final String sql = """
                  select p.id,
                         p.title,
                         p.description,
                         /* simple score: title hit gets +1, earlier position gets small boost */
                         (case when lower(p.title) like lower(:pattern) then 1.0 else 0.0 end)
                         + greatest(0.0, (50 - nullif(position(lower(:term) in lower(p.title)),0)) / 100.0)
                         as score
                    from products p
                   where lower(p.title) like lower(:pattern)
                      or lower(p.description) like lower(:pattern)
                order by
                     (lower(p.title) like lower(:pattern)) desc,
                     nullif(position(lower(:term) in lower(p.title)),0) asc nulls last,
                     p.id asc
                   limit :limit
                """;

        return template.getDatabaseClient().sql(sql)
                .bind("pattern", pattern)
                .bind("term", q)
                .bind("limit", limit)
                .map(this::mapRowToSearchResult)
                .all()
                .doOnSubscribe(s -> log.debug("SQL[ilike] term='{}' pattern='{}' limit={}", q, pattern, limit));
    }

    private Mono<Long> countMatches(String term) {
        final DatabaseClient client = template.getDatabaseClient();
        final String sql = """
                with q as (select websearch_to_tsquery(CAST(:cfg AS regconfig), CAST(:term AS text)) as query)
                select count(*) as cnt
                  from products p, q
                 where p.tsv @@ q.query
                """;

        return client.sql(sql)
                .bind("cfg", CFG)
                .bind("term", term)
                .map((row, md) -> getRequired(row, "cnt", Number.class).longValue())
                .one()
                .doOnSubscribe(s -> log.debug("SQL[count] executing; cfg='{}', term='{}'", CFG, term))
                .doOnSuccess(cnt -> log.debug("SQL[count] result -> {}", cnt))
                .doOnError(e -> log.warn("SQL[count] error term='{}': {}", term, e.toString()));
    }

    private SearchResult mapRowToSearchResult(Row row, RowMetadata md) {
        Long id = getRequired(row, "id", Long.class);
        String title = getRequired(row, "title", String.class);
        String description = getRequired(row, "description", String.class);
        // score can be null in pathological cases; default to 0.0
        Number score = getOptional(row, "score", Number.class);
        double s = score == null ? 0.0 : score.doubleValue();
        return new SearchResult(id, title, description, s);
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
