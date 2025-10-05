create extension if not exists pg_trgm;

-- Composite index that helps lower(title) and lower(description) ILIKE patterns
create index if not exists idx_products_title_trgm
    on products using gin (lower(title) gin_trgm_ops);

create index if not exists idx_products_desc_trgm
    on products using gin (lower(description) gin_trgm_ops);
