package com.github.dimitryivaniuta.gateway.search.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * Result item returned by the search endpoints/WS.
 * <p>
 * Fields:
 *  - id:    database identifier of the product
 *  - title: display title
 *  - score: ranking score (higher is better). Not a percentage; DB-specific (ts_rank).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchResult(
        @JsonProperty("id")    Long id,
        @JsonProperty("title") String title,
        @JsonProperty("description") String description,
        @JsonProperty("score") double score
) implements Serializable {

    /**
     * Compact canonical constructor with light normalization:
     * - trims title if non-null
     * - coerces non-finite scores (NaN/Inf) to 0.0
     */
    public SearchResult {
        if (title != null) title = title.trim();
        if (Double.isNaN(score) || Double.isInfinite(score)) score = 0.0d;
    }

    /** Static factory for readability. */
    public static SearchResult of(Long id, String title, String description, double score) {
        return new SearchResult(id, title, description, score);
    }

    /** Convenient updater when only the score changes. */
    public SearchResult withScore(double newScore) {
        return new SearchResult(this.id, this.title, this.description, newScore);
    }
}
