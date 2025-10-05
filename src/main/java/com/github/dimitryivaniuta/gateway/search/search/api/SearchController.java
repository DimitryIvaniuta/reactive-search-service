package com.github.dimitryivaniuta.gateway.search.search.api;

import com.github.dimitryivaniuta.gateway.search.api.dto.SearchResult;
import com.github.dimitryivaniuta.gateway.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService service;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<SearchResult> stream(@RequestParam String userId) {
        return service.userQueryStream(userId)
                .debounce(Duration.ofMillis(300))
                .switchMap(service::lookup);
    }

    @PostMapping("/keystroke")
    public void keystroke(@RequestParam String userId, @RequestBody String term) {
        service.submitQuery(userId, term);
    }

    @GetMapping
    public Flux<SearchResult> query(@RequestParam String q) {
        return service.lookup(q);
    }
}
