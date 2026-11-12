# ![RealWorld Example App using Kotlin and Spring](example-logo.png)

[![Actions](https://github.com/gothinkster/spring-boot-realworld-example-app/workflows/Java%20CI/badge.svg)](https://github.com/gothinkster/spring-boot-realworld-example-app/actions)

> ### Spring boot + MyBatis codebase containing real world examples (CRUD, auth, advanced patterns, etc) that adheres to the [RealWorld](https://github.com/gothinkster/realworld-example-apps) spec and API.

This codebase was created to demonstrate a fully fledged full-stack application built with Spring boot + Mybatis including CRUD operations, authentication, routing, pagination, and more.

For more information on how to this works with other frontends/backends, head over to the [RealWorld](https://github.com/gothinkster/realworld) repo.

# *NEW* GraphQL Support  

Following some DDD principles. REST or GraphQL is just a kind of adapter. And the domain layer will be consistent all the time. So this repository implement GraphQL and REST at the same time.

The GraphQL schema is https://github.com/gothinkster/spring-boot-realworld-example-app/blob/master/src/main/resources/schema/schema.graphqls and the visualization looks like below.

![](graphql-schema.png)

And this implementation is using [dgs-framework](https://github.com/Netflix/dgs-framework) which is a quite new java graphql server framework.
# How it works

The application uses Spring Boot (Web, Mybatis).

* Use the idea of Domain Driven Design to separate the business term and infrastructure term.
* Use MyBatis to implement the [Data Mapper](https://martinfowler.com/eaaCatalog/dataMapper.html) pattern for persistence.
* Use [CQRS](https://martinfowler.com/bliki/CQRS.html) pattern to separate the read model and write model.

And the code is organized as this:

1. `api` is the web layer implemented by Spring MVC
2. `core` is the business model including entities and services
3. `application` is the high-level services for querying the data transfer objects
4. `infrastructure`  contains all the implementation classes as the technique details

# Security

Integration with Spring Security and add other filter for jwt token process.

The secret key is stored in `application.properties`.

# Database

It uses a ~~H2 in-memory database~~ sqlite database (for easy local test without losing test data after every restart), can be changed easily in the `application.properties` for any other database.

## Article keyword search

`GET /articles?keyword=sqlite%20transactions&tag=java&offset=0&limit=20`

The optional `keyword` searches title, description, and body using SQLite FTS5. The bundled
SQLite JDBC driver supports FTS5; no external service is needed. Flyway V2 rebuilds the index
for existing articles and installs insert/update/delete triggers. The external-content index
uses SQLite's integer `rowid`, not the article's UUID. Index changes participate in the article
write transaction, including rollback.
V3 adds a covering index on article-tag relations for filtering and DTO hydration. Candidate/count
queries use existence predicates rather than unconditional tag/favorite joins, avoiding row
multiplication and expensive DISTINCT operations.
Connections allow up to 256 MiB of SQLite memory-mapped reads (`PRAGMA mmap_size=268435456`),
reducing copies during large index scans; this is a mapping limit, not an upfront memory allocation.

Keywords are case-insensitive under FTS5's default `unicode61` tokenizer. Unicode whitespace-separated
terms are ANDed; each is quoted/escaped as literal text, so operators, wildcards, and column
selectors are not exposed as query syntax. Punctuation is handled by the tokenizer (a term such
as `spring-boot` becomes a phrase). Empty/whitespace-only input means no search filter; a
punctuation-only search returns no matches. There is no substring matching or stemming.
Inputs are bounded to 4,096 UTF-16 code units and 64 Unicode-whitespace-separated terms.
Oversized inputs, too many terms, or embedded NUL characters are rejected before SQL execution
with HTTP 400. Malformed-looking FTS expressions otherwise remain literal text, not executable
operators. Regression and HTTP checks cover the boundaries, quotes, incomplete expressions,
SQL-like inputs, and Unicode whitespace.

**Relevance contract: title hits ALWAYS precede description-only hits, and description hits
ALWAYS precede body-only hits. Include this strict requirement in future implementation prompts;
BM25 weights alone do not guarantee it.** The highest matching field determines the tier:
any matching query term in the title qualifies for title-tier, even if other terms match elsewhere.
All query terms must still match somewhere in the article. Tests use adversarial document lengths
and term frequencies, not just a single title-vs-body example.

Both pagination methods use one complete order: **field tier ascending (title = 0, description = 1,
body = 2), weighted BM25 ascending, article ID ascending**. IDs use SQLite's existing binary text
order without UUID parsing or normalization, so legacy textual IDs work too. Filtering and ranking happen before
pagination; DTO hydration preserves the selected ID order. Field-only BM25 calls with other column
weights zero detect matching fields: a hit has a negative score, while no hit scores zero. Their
magnitude never determines the tier.
`articles_fts.rank MATCH 'bm25(10.0, 5.0, 1.0)'` maps FTS5's hidden `rank` column to
`bm25(articles_fts, 10.0, 5.0, 1.0)`. Title, description, and body have weights 10, 5, and 1;
these boosts rank articles **within a tier** using term frequency, rarity, and document length,
but cannot override field priority. `articlesCount` counts matches after keyword, tag, author,
and favorited filters, independently of the offset page; keyword-only counts read the FTS index.

The keyword-aware `findRecentArticlesWithCursor` overload takes
`CursorPageParameter<ArticleSearchCursor>`. Its `search-v3` tokens encode the field tier, exact BM25
score, opaque article ID, and a fingerprint of the normalized keyword expression and tag/author/favorited
filters. Scores round-trip losslessly. Changed-query/filter or malformed cursors are rejected.
Get tokens from the returned pager's `getStartCursor()`/`getEndCursor()` and parse with
`ArticleSearchCursor.parse(token)`; search positions belong to the pager, not the cached article
DTO's legacy timestamp cursor. NEXT seeks strictly after `(tier, score, ID)`. PREV seeks strictly
before it, fetches in reverse order, and restores the shared ascending order before returning.
Equal-score articles therefore do not disappear at page boundaries, including across tiers.
IDs are individually Base64URL-encoded inside the token so Unicode, newlines, and other delimiter
characters round-trip exactly. Tokens are capped at 16 KiB; invalid UTF-8 and positions that cannot
round-trip within that limit are rejected. Legacy-ID migration and NEXT/PREV parity have regression
coverage, including the non-UUID backfill fixture. Old `search-v1`/`search-v2` tokens are rejected
because the ordering/ID encoding changed.

Use the existing five-argument service overload and `CursorPageParameter<DateTime>` when there
is no keyword (the search-specific overload requires a nonblank keyword). Non-search timestamp
pagination and GraphQL callers retain their existing behavior and timestamp-boundary limitations.
REST exposes keyword search through offset/limit; this change does not add GraphQL search fields
or a REST cursor endpoint.

Parity assumes the same keyword/filters and an unchanged dataset. BM25 depends on corpus-wide
statistics, so writes can change scores even for other documents; these cursors do not retain a
database snapshot. Restart search traversal after changes if a consistent snapshot is required.
Cursor fingerprints detect accidental reuse across queries; tokens are not authorization tokens.

### Reproducible search benchmark

Run the opt-in benchmark separately from the normal regression suite:

```sh
./gradlew generateJava articleSearchBenchmark
```

`ArticleSearchBenchmark` creates an isolated, temporary **on-disk** SQLite database using the
application's Flyway migrations and bundled JDBC driver. It generates 100,000 deterministic
UUID articles, 100 authors, 10 tags, two tags per article (200,000 relations), and 20,000 favorites.
Each body contains about 1.2 KB of repeated prose. `quartz` appears in 100 titles, `sqlite` in
10,000 descriptions, and `article` in all 100,000 titles. Inserts use batches in one transaction
with FTS triggers enabled; `ANALYZE` runs after seeding. Production `dev.db` is never used.

Measurements use `System.nanoTime()` around the actual `ArticleQueryService` call, including
MyBatis/JDBC, ranked ID selection, total count, DTO/tag/author hydration, favorite counts,
ordering, and result assertions for anonymous readers. They exclude seeding, application startup,
HTTP/network/JSON serialization, and authenticated viewer-specific lookups. Each scenario reports
its first call separately, then 10 warmups and 50 measured calls (p50/p95/max); pages contain 20
articles. Calls run serially without a surrounding transaction, so MyBatis sessions/caches are
not reused between calls. OS/SQLite page caches are warm; the first call is **not** a guaranteed
cold-disk measurement. The serial benchmark asserts p95 below 200 ms per scenario, not a universal SLA
for arbitrary hardware, concurrency, cold caches, or datasets.

Scenarios cover rare/medium/broad and multi-term keywords, keyword combined separately with tag,
author, or favorite, offset 1,000, no matches, and NEXT/PREV rank-cursor queries after the 1,000th
ranked match for both medium and broad keywords. Cursor boundary setup and corresponding expected
offset pages are computed outside timed calls; each measured cursor result must exactly match
its corresponding offset page's IDs and ordering.

The same run then starts **20 concurrent HTTP/1.1 clients** against the real embedded server on
loopback. Each client performs two warmups, waits at a common start barrier, and makes ten measured
requests serially (200 samples per workload, 600 total). Rare, medium, and broad queries use
`GET /articles?keyword=...&limit=20`. Timings include HTTP, JSON serialization/parsing, response
assertions, and connection-pool waiting; the default Hikari pool has ten connections. Requests have
a 30-second timeout. The harness checks HTTP 200, article counts/page sizes, zero errors, and reports
p50/p95/max plus aggregate throughput. Concurrency latency is measured, **not** asserted below 200 ms.
This is a short, closed-loop, read-only load test, not a sustained production/concurrent-write test.

Gradle writes the detailed output to `build/test-results/articleSearchBenchmark/` and the HTML
report to `build/reports/tests/articleSearchBenchmark/`.

#### Measured results

Session VM: Ubuntu/Linux 6.8, 8 vCPUs (Intel Xeon), 32 GB RAM, Java 11.0.33, SQLite 3.36.0
via sqlite-jdbc 3.36.0.3. Default application connection settings, including the 256 MiB mmap
limit above; no benchmark-only database tuning. All 13 serial scenarios passed the 200 ms p95 threshold.
Latency in milliseconds:

| Scenario | First call | p50 | p95 | Max of 50 |
| --- | ---: | ---: | ---: | ---: |
| Rare keyword, 100 matches | 65.58 | 2.10 | 2.78 | 2.99 |
| Medium keyword, 10K matches | 16.85 | 11.69 | 14.09 | 15.68 |
| Broad keyword, 100K matches | 97.57 | 91.52 | 95.06 | 114.87 |
| Multiple keywords | 2.01 | 1.66 | 1.81 | 1.84 |
| Keyword + tag | 34.37 | 29.76 | 32.14 | 33.86 |
| Keyword + author | 9.76 | 7.60 | 7.85 | 7.92 |
| Keyword + favorite | 24.11 | 23.55 | 35.40 | 37.52 |
| Offset 1,000 | 14.03 | 13.73 | 16.71 | 23.24 |
| No match | 0.39 | 0.10 | 0.12 | 0.18 |
| Medium keyword cursor NEXT | 18.94 | 12.70 | 13.88 | 15.33 |
| Medium keyword cursor PREV | 11.05 | 10.97 | 11.81 | 12.09 |
| Broad keyword cursor NEXT | 107.05 | 109.78 | 114.64 | 125.45 |
| Broad keyword cursor PREV | 90.86 | 89.17 | 94.24 | 99.36 |

20-client HTTP results (200 measured requests per workload, zero errors):

| Keyword / matches | p50 ms | p95 ms | Max ms | Requests/sec |
| --- | ---: | ---: | ---: | ---: |
| quartz / 100 | 21.33 | 42.71 | 61.19 | 785.26 |
| sqlite / 10K | 56.28 | 97.41 | 124.84 | 305.88 |
| article / 100K | 264.46 | 400.32 | 438.39 | 69.60 |

**The broad search does not meet 200 ms p95 at 20 concurrent clients.** Do not extrapolate the
single-reader result into a concurrency SLA. Earlier development repeats also exceeded 200 ms p95
for broad cursor queries (up to 268.36 ms) even when other runs passed; latency varies between runs.

The initial unindexed-join implementation exceeded 3 seconds p95 even for rare matches.
The covering tag-relation index, existence predicates, index-only keyword count, FTS ranking,
and production mmap setting are important to the measured performance. These warm-cache tests
do not establish performance for arbitrary concurrent production traffic or cold disks.

# Getting started

You'll need Java 11 installed.

    ./gradlew bootRun

To test that it works, open a browser tab at http://localhost:8080/tags .  
Alternatively, you can run

    curl http://localhost:8080/tags

# Try it out with [Docker](https://www.docker.com/)

You'll need Docker installed.
	
    ./gradlew bootBuildImage --imageName spring-boot-realworld-example-app
    docker run -p 8081:8080 spring-boot-realworld-example-app

# Try it out with a RealWorld frontend

The entry point address of the backend API is at http://localhost:8080, **not** http://localhost:8080/api as some of the frontend documentation suggests.

# Run test

The repository contains a lot of test cases to cover both api test and repository test.

    ./gradlew test

# Code format

Use spotless for code format.

    ./gradlew spotlessJavaApply

# Help

Please fork and PR to improve the project.
