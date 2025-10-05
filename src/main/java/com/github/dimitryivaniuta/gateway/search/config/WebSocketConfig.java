package com.github.dimitryivaniuta.gateway.search.config;

import com.github.dimitryivaniuta.gateway.search.ws.SearchWsHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class WebSocketConfig {

    private final SearchWsHandler searchWsHandler;

    @Bean
    public SimpleUrlHandlerMapping wsMapping() {
        // Map endpoint: ws://host:8080/ws/search?userId=...
        return new SimpleUrlHandlerMapping(Map.of("/ws/search", (WebSocketHandler) searchWsHandler), 10);
    }

    @Bean
    public WebSocketHandlerAdapter wsAdapter() {
        return new WebSocketHandlerAdapter();
    }
}