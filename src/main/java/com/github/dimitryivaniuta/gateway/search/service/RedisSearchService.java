package com.github.dimitryivaniuta.gateway.search.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.ReactiveSubscription.ChannelMessage;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class RedisSearchService {
    private final ReactiveStringRedisTemplate redis;
    private static String channel(String userId) { return "search:user:" + userId; }

    public Flux<String> userQueryStream(String userId) {
        return redis.listenToChannel(channel(userId))
                .map(ChannelMessage::getMessage)
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isEmpty())
                .distinctUntilChanged();
    }

    public void submitQuery(String userId, String query) {
        redis.convertAndSend(channel(userId), query == null ? "" : query).subscribe();
    }
}
