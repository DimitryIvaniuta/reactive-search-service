# Reactive Search — Backend

A production‑ready **reactive search microservice** built with **Java 21**, **Spring Boot 3.5**, **WebFlux**, **PostgreSQL (R2DBC)**, **Redis (reactive Pub/Sub)**, and **Flyway**.

The service ingests keystrokes over **WebSocket** (or HTTP), debounces them, executes ranked search in Postgres (FTS with an incremental‑typing prefix fallback), and streams results back over **SSE** or **WebSocket**.

---

## ⚙️ Tech Stack

* **Java 21**
* **Spring Boot 3.5.x** (WebFlux, Validation, R2DBC)
* **PostgreSQL** with **R2DBC** (reactive) + Flyway migrations
* **Redis** (reactive Lettuce) for per‑user keystroke Pub/Sub
* **Lombok**
* Build: **Gradle (Groovy)**

---

## 📦 Project layout (key modules)

```
src/main/java/com/github/dimitryivaniuta/gateway/search/
├─ SearchServiceApplication.java
├─ api/
│  ├─ SearchController.java          # SSE + HTTP endpoints
│  └─ dto/SearchResult.java          # immutable DTO (record)
├─ config/
│  ├─ SearchProps.java               # @ConfigurationProperties(app.search)
│  └─ DotenvEnvironmentPostProcessor.java  # optional .env loader (dev)
├─ domain/Product.java               # R2DBC entity
├─ repo/
│  ├─ ProductRepository.java         # R2DBC + custom fragment
│  ├─ ProductSearchRepository.java   # custom search API
│  └─ ProductSearchRepositoryImpl.java# FTS + prefix tsquery logic
├─ service/
│  ├─ SearchService.java             # abstraction
│  ├─ DbSearchService.java           # routes to repo / Redis
│  └─ RedisSearchService.java        # reactive Pub/Sub
└─ ws/
   └─ SearchWsHandler.java           # WebSocket handler
```

---

## 🔧 Prerequisites

* **JDK 21**
* **Docker** (for Postgres/Redis)
* **Gradle** (wrapper provided)

---

## 🚀 Quick start

### 1) Start databases (Docker)

Create `docker-compose.yml` (or reuse your existing one):

```yaml
authentication: {}
services:
  postgres:
    image: postgres:16
    container_name: search-postgres
    environment:
      POSTGRES_DB: search
      POSTGRES_USER: search_user
      POSTGRES_PASSWORD: search_pass
    ports: ["5441:5432"]
    volumes:
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7
    container_name: search-redis
    ports: ["6379:6379"]

volumes:
  pgdata: {}
```

Start:

```bash
docker compose up -d
```

### 2) Configure application

`src/main/resources/application.yml` (excerpt):

```yaml
spring:
  r2dbc:
    url: r2dbc:pool:postgresql://${DB_HOST:localhost}:${DB_PORT:5441}/${DB_NAME:search}
    username: ${DB_USER:search_user}
    password: ${DB_PASSWORD:search_pass}
  flyway:
    enabled: true
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5441}/${DB_NAME:search}
    user: ${DB_USER:search_user}
    password: ${DB_PASSWORD:search_pass}

logging:
  level:
    org.springframework.r2dbc.core: DEBUG
    io.r2dbc.postgresql.QUERY: DEBUG
    io.r2dbc.postgresql.PARAM: DEBUG

app:
  search:
    debounce-window: 300ms
    max-results: 20
    fts-config: english
```

### 3) (Optional) Local `.env` support without external libs

Implement `DotenvEnvironmentPostProcessor` and register in `src/main/resources/META-INF/spring.factories`:

```
org.springframework.boot.env.EnvironmentPostProcessor=\
com.github.dimitryivaniuta.gateway.search.config.DotenvEnvironmentPostProcessor
```

Your `.env` at project root:

```
DB_HOST=localhost
DB_PORT=5441
DB_NAME=search
DB_USER=search_user
DB_PASSWORD=search_pass
```

> Note: This post‑processor **adds** properties only if not already set via real env or CLI.

### 4) Build & run

```bash
./gradlew clean build
./gradlew bootRun
# or
java -jar build/libs/search-service-*.jar
```

---

## 🗄️ Database schema & indexing

### Flyway migrations (sketch)

`V1__products.sql`:

```sql
create table if not exists products (
  id bigserial primary key,
  title text not null,
  description text not null default ''
);
```

`V2__fts.sql`:

```sql
-- Ensure extension(s)
create extension if not exists pg_trgm;        -- optional, for ILIKE acceleration

-- Stored tsvector (generated column) + GIN index
alter table products
  add column if not exists tsv tsvector
    generated always as (
      setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
      setweight(to_tsvector('english', coalesce(description, '')), 'B')
    ) stored;

create index if not exists idx_products_tsv_gin on products using gin (tsv);

-- Optional trigram indexes for short/ILIKE searches
create index if not exists idx_products_title_trgm on products using gin (lower(title) gin_trgm_ops);
create index if not exists idx_products_desc_trgm  on products using gin (lower(description) gin_trgm_ops);
```

---

## 🔎 Search logic (summary)

* **Incremental typing ("sam" → Samsung):**

    * For very short input (configurable), route to **ILIKE** `%term%` (case‑insensitive) — fast with trigram.
    * For 2+ tokens or longer input, build **prefix tsquery** via `to_tsquery('english', 'sam:* & gal:*')` and use `ts_rank` for ordering. Fallback to `websearch_to_tsquery` for complex phrases.
* **Case insensitivity:** FTS is case‑insensitive; ILIKE path lower‑cases both sides.

---

## 🌐 Endpoints

### 1) HTTP – one‑shot lookup

```
GET /api/search?q=iphone
Accept: application/x-ndjson | application/json
```

**Curl**

```bash
curl "http://localhost:8080/api/search?q=iphone"
```

### 2) SSE – debounced results stream (per user)

```
GET /api/search/stream?userId=u1
Accept: text/event-stream
```

Keep this open; results arrive as the user types (debounced).

### 3) WS – keystrokes in / results out

```
WS  ws://localhost:8080/ws/search?userId=u1
```

Send **text frames** containing the current query string (each keystroke). Server returns JSON arrays of `SearchResult` after the debounce window.

### 4) Keystroke via HTTP (if not using WS)

```
POST /api/search/keystroke?userId=u1
Content-Type: text/plain
Body: iphone 15
```

**Curl**

```bash
curl -X POST "http://localhost:8080/api/search/keystroke?userId=u1" \
  -H "Content-Type: text/plain" \
  --data "iphone 15"
```

> If you send JSON by mistake you’ll get **415**. Add `consumes` JSON or set `Content-Type: text/plain`.

---

## 🧩 Key classes & behavior

### `SearchProps` (global config)

```yaml
app:
  search:
    debounce-window: 300ms
    max-results: 20
    fts-config: english
```

Injectable anywhere via constructor; enable with `@ConfigurationPropertiesScan`.

### `RedisSearchService`

* Subscribes to per‑user channel `search:user:{userId}`
* Publishes keystrokes (fire‑and‑forget) and exposes a **hot `Flux<String>`** via `listenToChannel()`

### `DbSearchService`

* `lookup(term)` → routes 1–2 chars to **ILIKE** fallback; longer to **FTS prefix tsquery**
* Returns `Flux<SearchResult>`

### `ProductSearchRepositoryImpl`

* **Prefix matching** for incremental typing:

    * build: `to_tsquery('english', 'sam:* & gal:*')`
    * rank: `ts_rank(tsv, query)`
* **Fallback**: `websearch_to_tsquery('english', :term)` when no safe tokens
* Supports paging & top‑N

---

## 🧪 Postman / curl testing

**WebSocket**

1. New → WebSocket → `ws://localhost:8080/ws/search?userId=u1`
2. Connect, send text frames: `iph` → `iphone` → `iphone 15`
3. Responses show as JSON arrays (debounced).

**SSE**

```bash
curl -N "http://localhost:8080/api/search/stream?userId=u1"
```

In another terminal, post a keystroke (see HTTP keystroke above).

**HTTP search**

```bash
curl "http://localhost:8080/api/search?q=sam"
```

---

## 🧰 Gradle (excerpt)

```groovy
dependencies {
  implementation 'org.springframework.boot:spring-boot-starter-webflux'
  implementation 'org.springframework.boot:spring-boot-starter-data-redis-reactive'
  implementation 'org.springframework.boot:spring-boot-starter-data-r2dbc'
  implementation 'io.r2dbc:r2dbc-pool'

  implementation 'org.flywaydb:flyway-core'
  implementation 'org.flywaydb:flyway-database-postgresql'
  runtimeOnly  'org.postgresql:r2dbc-postgresql'
  runtimeOnly  'org.postgresql:postgresql'

  implementation 'org.springframework.boot:spring-boot-starter-validation'
  compileOnly 'org.projectlombok:lombok'
  annotationProcessor 'org.projectlombok:lombok'
  annotationProcessor 'org.springframework.boot:spring-boot-configuration-processor'

  testImplementation 'org.springframework.boot:spring-boot-starter-test'
  testImplementation 'io.projectreactor:reactor-test'
}
```

> If running from IDE, ensure the **R2DBC Postgres driver** is on the runtime classpath. If needed, change it from `runtimeOnly` to `implementation`.

---

## 🛠️ Troubleshooting

**A) R2DBC driver error**: *Available drivers: [pool]*
➡ Add `org.postgresql:r2dbc-postgresql` on runtime classpath and use `r2dbc:pool:postgresql://…` URL.

**B) 500 BadSqlGrammar** (*websearch_to_tsquery* not found or wrong types)
➡ Use Postgres ≥ 11 and **cast**: `websearch_to_tsquery(CAST(:cfg AS regconfig), CAST(:term AS text))`.

**C) No results for `sam`**
➡ Use **prefix tsquery** on tokens: `to_tsquery('english','sam:*')` (already implemented). Optionally route 1–2 chars to ILIKE.

**D) 415 Unsupported Media Type on `/keystroke`**
➡ Send `Content-Type: text/plain`, body is the raw term.

**E) Missing `debounce(...)`**
➡ Use Reactor’s `sampleTimeout(q -> Mono.delay(window))` (already used) or upgrade Reactor.

**F) `.env` not applied**
➡ Spring doesn’t read `.env` by default; use the included **EnvironmentPostProcessor** (dev) or set real env vars.

---

## 🔒 Observability & Ops (optional)

* Add Actuator: `implementation 'org.springframework.boot:spring-boot-starter-actuator'`
* Expose health/metrics in `application.yml`

---

## 📜 License

MIT

---

## ✨ Credits

Architecture & implementation tuned for reactive end‑to‑end UX: Redis Pub/Sub for keystrokes, debounced WS/SSE delivery, and PostgreSQL FTS with incremental‑typing support.

## Contact

**Dimitry Ivaniuta** — [dzmitry.ivaniuta.services@gmail.com](mailto:dzmitry.ivaniuta.services@gmail.com) — [GitHub](https://github.com/DimitryIvaniuta)

