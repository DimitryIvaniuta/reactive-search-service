package com.github.dimitryivaniuta.gateway.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.search")
public record SearchProps(
        @DefaultValue("300ms") Duration debounceWindow,
        @DefaultValue("20")     int      maxResults,
        @DefaultValue("english") String  ftsConfig
) {}