package com.github.dimitryivaniuta.gateway.search.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.gateway.search.service.SearchService;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtension;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SearchWsHandler implements WebSocketHandler {

    private final SearchService searchService;     // uses DbSearchService lookup()
    private final ObjectMapper mapper;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // userId extracted from query: /ws/search?userId=abc
        String userId = session.getHandshakeInfo().getUri().getQuery();
        userId = extractUserId(userId); // simple parser below

        // 1) Inbound text → publish keystrokes
        Mono<Void> inbound = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .doOnNext(term -> searchService.submitQuery(userId, term)) // Redis Pub/Sub
                .then();

        // 2) Outbound: subscribe to user's Pub/Sub channel → debounce → lookup → send JSON
        Flux<WebSocketMessage> outbound = searchService.userQueryStream(userId)
                .debounce(Duration.ofMillis(250))            // slightly tighter than SSE (UX snappy)
                .switchMap(searchService::lookup)                   // cancels stale lookups
                .onBackpressureLatest()                       // keep latest result set
                .bufferTimeout(10, Duration.ofMillis(10))     // tiny batch to frame messages
                .filter(list -> !list.isEmpty())
                .map(list -> toJson(list))
                .map(session::textMessage);

        return session.send(outbound)
                .and(inbound); // run both directions
    }

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String extractUserId(String q) {
        if (q == null) throw new IllegalArgumentException("userId is required");
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals("userId") && !kv[1].isBlank()) return kv[1];
        }
        throw new IllegalArgumentException("userId is required");
    }

    @Override public List<WebSocketExtension> getSupportedExtensions() { return List.of(); }
}
