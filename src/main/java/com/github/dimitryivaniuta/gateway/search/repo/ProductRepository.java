package com.github.dimitryivaniuta.gateway.search.repo;

import com.github.dimitryivaniuta.gateway.search.domain.Product;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

/**
 * Aggregate repository for Product plus full-text search extension.
 */
public interface ProductRepository
        extends R2dbcRepository<Product, Long>, ProductSearchRepository {
}