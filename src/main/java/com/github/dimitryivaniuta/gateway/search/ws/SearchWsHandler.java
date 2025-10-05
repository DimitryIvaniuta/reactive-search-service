package com.github.dimitryivaniuta.gateway.search.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.gateway.search.api.dto.SearchResult;
import com.github.dimitryivaniuta.gateway.search.config.SearchProps;
import com.github.dimitryivaniuta.gateway.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.socket.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SearchWsHandler implements WebSocketHandler {

    private static final int BATCH_SIZE = 10;
    private static final Duration BATCH_WINDOW = Duration.ofMillis(10);

    private final SearchService searchService;
    private final SearchProps props;          // provides debounceWindow(), maxResults(), etc.
    private final ObjectMapper mapper;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        final String userId = extractUserId(session.getHandshakeInfo().getUri());
        log.debug("WS connected userId={}", userId);

        // Inbound: text frames -> trimmed terms -> publish to Redis Pub/Sub
        Mono<Void> inbound =
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .doOnNext(term -> searchService.submitQuery(userId, term))
                        .onErrorResume(ex -> {
                            log.warn("WS inbound error userId={}: {}", userId, ex.toString());
                            return Mono.empty();
                        })
                        .then();

        // Outbound: subscribe to user stream -> debounce via sampleTimeout -> lookup -> JSON
        Flux<WebSocketMessage> outbound =
                searchService.userQueryStream(userId)
                        // Debounce semantics: emit the last term after the quiet period
                        .sampleTimeout(q -> Mono.delay(props.debounceWindow()))
                        // Cancel older lookups when a new term arrives
                        .switchMap(searchService::lookup)
                        // If client is slow, keep only the latest results
                        .onBackpressureLatest()
                        // Small batch to reduce frame overhead
                        .bufferTimeout(BATCH_SIZE, BATCH_WINDOW)
                        .filter(batch -> !batch.isEmpty())
                        .map(this::toJson)
                        .map(session::textMessage)
                        .doOnError(ex -> log.warn("WS outbound error userId={}: {}", userId, ex.toString()))
                        .doOnTerminate(() -> log.debug("WS outbound terminated userId={}", userId));

        // Send results and consume inbound simultaneously
        return session.send(outbound)
                .and(inbound)
                .doFinally(sig -> log.debug("WS disconnected userId={} signal={}", userId, sig));
    }

    /* ---------------- helpers ---------------- */

    private String toJson(List<SearchResult> list) {
        try {
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            // Fail-soft: log and return empty array so client remains connected
            log.warn("JSON serialization failed: {}", e.toString());
            return "[]";
        }
    }

    private static String extractUserId(URI uri) {
        MultiValueMap<String, String> q = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        String id = q.getFirst("userId");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("userId is required in query, e.g. /ws/search?userId=u1");
        }
        return id;
    }

//    @Override
//    public List<WebSocketExtension> getSupportedExtensions() {
//        return List.of(); // no special extensions
//    }
}
