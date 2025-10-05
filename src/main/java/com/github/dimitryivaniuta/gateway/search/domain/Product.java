package com.github.dimitryivaniuta.gateway.search.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Domain aggregate for a catalog item searchable via PostgreSQL FTS.
 *
 * Table (from migrations):
 *   products (
 *     id          bigserial primary key,
 *     title       text not null,
 *     description text not null default '',
 *     tsv         tsvector generated always as (...) stored   -- managed by DB, not mapped here
 *   )
 *
 * Notes:
 * - We do NOT map the generated 'tsv' column; PostgreSQL maintains it. The GIN index uses it.
 * - Validation annotations are for request/DTO-to-entity flows; repository calls don’t trigger them automatically.
 * - Equals/HashCode use the surrogate key 'id' when present.
 */
@Table("products")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Product {

    /** Surrogate key. Null for new entities; set by Postgres on insert. */
    @Id
    @EqualsAndHashCode.Include
    private Long id;

    /** Display title (indexed with highest weight in FTS). */
    @NotBlank
    @Size(max = 10_000) // defensive bound; text column has no hard limit
    @Column("title")
    private String title;

    /** Description (indexed with lower weight in FTS). */
    @NotBlank
    @Size(max = 100_000) // defensive bound
    @Column("description")
    private String description;

    /** Factory with light normalization (trim); leaves id null for inserts. */
    public static Product of(String title, String description) {
        return Product.builder()
                .title(safeTrim(title))
                .description(safeTrim(description))
                .build();
    }

    /** Copy-with helpers for fluent updates (immutability-style API on a mutable entity). */
    public Product withTitle(String newTitle) {
        this.title = safeTrim(newTitle);
        return this;
    }

    public Product withDescription(String newDescription) {
        this.description = safeTrim(newDescription);
        return this;
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }
}
