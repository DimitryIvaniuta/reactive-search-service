package com.github.dimitryivaniuta.gateway.search.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisSearchService {

    private final ReactiveStringRedisTemplate redis;

    public Flux<String> userQueryStream(String userId) {
        final String ch = channel(requireUser(userId));
        // Hot stream from Redis Pub/Sub, per-user channel
        return redis.listenToChannel(ch)                 // Flux<ChannelMessage<String, String>>
                .map(cm -> cm.getMessage())                  // <-- let inference work; no explicit types
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isEmpty())
                .distinctUntilChanged();
    }

    public void submitQuery(String userId, String query) {
        final String ch = channel(requireUser(userId));
        final String payload = query == null ? "" : query;
        // Fire-and-forget publish; log but don't break UX on errors
        redis.convertAndSend(ch, payload)
                .onErrorResume(e -> {
                    log.warn("Redis publish failed for channel={} err={}", ch, e.toString());
                    return Mono.empty();
                })
                .subscribe();
    }

    private static String channel(String userId) {
        return "search:user:" + userId;
    }

    private static String requireUser(String userId) {
        if (userId == null || userId.isBlank())
            throw new IllegalArgumentException("userId must be provided");
        return userId;
    }
}
