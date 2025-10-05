package com.github.dimitryivaniuta.gateway.search.api;

import com.github.dimitryivaniuta.gateway.search.api.dto.SearchResult;
import com.github.dimitryivaniuta.gateway.search.config.SearchProps;
import com.github.dimitryivaniuta.gateway.search.service.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/search")
@Validated
@Slf4j
public class SearchController {

    private final SearchProps searchProps;

    private final SearchService service;

    public SearchController(
            SearchService service,
            SearchProps searchProps) {
        this.service = service;
        this.searchProps = searchProps;
    }

    /**
     * Server-Sent Events: stream debounced results as user types.
     *
     * Example:
     *   curl -N "http://localhost:8080/api/search/stream?userId=u1"
     *   # then POST keystrokes to /api/search/keystroke or use your WS endpoint
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<SearchResult> stream(@RequestParam String userId) {
        return debouncedResultsFor(userId)
                .doOnSubscribe(s -> log.debug("SSE subscribed userId={}", userId))
                .doFinally(sig -> log.debug("SSE finished userId={} signal={}", userId, sig));
    }

    /**
     * One-shot search (non-streaming).
     * Example: GET /api/search?q=iphone
     */
    @GetMapping(produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<SearchResult> query(@RequestParam("q") String term) {
        return service.lookup(term);
    }

    /**
     * Optional: push a keystroke if you’re not using WS.
     * Example:
     *   curl -X POST "http://localhost:8080/api/search/keystroke?userId=u1" \
     *        -H "Content-Type: text/plain" -d "iphone"
     */
    @PostMapping(path = "/keystroke", consumes = MediaType.TEXT_PLAIN_VALUE)
    public void keystroke(@RequestParam String userId, @RequestBody String term) {
        service.submitQuery(userId, term);
    }

    private Flux<SearchResult> debouncedResultsFor(String userId) {
        return service.userQueryStream(userId)
                // Debounce via sampleTimeout: for each query q, start a timer; if another arrives before
                // the timer elapses, drop q; otherwise emit q after the quiet period.
                .sampleTimeout(q -> Mono.delay(searchProps.debounceWindow()))
                .switchMap(service::lookup)          // cancel stale lookups when a new term arrives
                .onBackpressureLatest()              // keep the most recent results if the client is slow
                .take(2000)                          // optional long-running stream guard
                .onErrorResume(ex -> {
                    log.warn("Search stream error userId={}: {}", userId, ex.toString());
                    return Flux.empty();
                });
    }
}
