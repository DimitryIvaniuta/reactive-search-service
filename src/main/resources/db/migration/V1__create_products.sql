create table if not exists products (
    id           bigserial primary key,
    title        text not null,
    description  text not null default '',
    -- Generated full-text vector (simple english config, adjust as needed)
    tsv          tsvector generated always as (
        setweight(to_tsvector('english', coalesce(title,'')), 'A') ||
        setweight(to_tsvector('english', coalesce(description,'')), 'B')
    ) stored
    );

-- GIN index for full-text search
create index if not exists idx_products_tsv on products using gin (tsv);
