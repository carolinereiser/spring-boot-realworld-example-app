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

**Both offset and cursor keyword searches use exactly the same global order:** weighted BM25
ascending (lower is better), then article UUID ascending to resolve equal scores. Filtering and
ranking happen **before** pagination; DTO hydration preserves the selected ID order.
`articles_fts.rank MATCH 'bm25(10.0, 5.0, 1.0)'` maps FTS5's hidden `rank` column to
`bm25(articles_fts, 10.0, 5.0, 1.0)`. Title, description, and body have weights 10, 5, and 1;
these are boosts, not absolute field priorities, since BM25 also considers term frequency,
term rarity, and document length. `articlesCount` counts all matches after keyword, tag, author,
and favorited filters, independently of the offset page; keyword-only counts read the FTS index.

The keyword-aware `findRecentArticlesWithCursor` overload takes
`CursorPageParameter<ArticleSearchCursor>`. Its `search-v1` tokens encode the exact BM25 score,
article UUID, and a fingerprint of the normalized keyword expression and tag/author/favorited
filters. Scores round-trip losslessly. Changed-query/filter or malformed cursors are rejected.
Get tokens from the returned pager's `getStartCursor()`/`getEndCursor()` and parse with
`ArticleSearchCursor.parse(token)`; search positions belong to the pager, not the cached article
DTO's legacy timestamp cursor. NEXT seeks strictly after `(score, UUID)`. PREV seeks strictly
before it, fetches in reverse order, and restores the shared ascending order before returning.
Equal-score articles therefore do not disappear at page boundaries.

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
cold-disk measurement. The benchmark asserts p95 below 200 ms per scenario, not a universal SLA
for arbitrary hardware, concurrency, cold caches, or datasets.

Scenarios cover rare/medium/broad and multi-term keywords, keyword combined separately with tag,
author, or favorite, offset 1,000, no matches, and NEXT/PREV rank-cursor queries after the 1,000th
ranked match for both medium and broad keywords. Cursor boundary setup and corresponding expected
offset pages are computed outside timed calls; each measured cursor result must exactly match
its corresponding offset page's IDs and ordering.
Gradle writes the detailed output to `build/test-results/articleSearchBenchmark/` and the HTML
report to `build/reports/tests/articleSearchBenchmark/`.

#### Measured results

Session VM: Ubuntu/Linux 6.8, 8 vCPUs (Intel Xeon), 32 GB RAM, Java 11.0.33, SQLite 3.36.0
via sqlite-jdbc 3.36.0.3. Default application connection settings, including the 256 MiB mmap
limit above; no benchmark-only database tuning. All 13 scenarios passed the 200 ms p95 threshold.
Latency in milliseconds:

| Scenario | First call | p50 | p95 | Max of 50 |
| --- | ---: | ---: | ---: | ---: |
| Rare keyword, 100 matches | 74.46 | 2.48 | 3.32 | 8.17 |
| Medium keyword, 10K matches | 16.32 | 11.14 | 15.58 | 20.46 |
| Broad keyword, 100K matches | 160.10 | 80.83 | 88.85 | 93.70 |
| Multiple keywords | 1.89 | 1.63 | 1.98 | 2.11 |
| Keyword + tag | 32.58 | 28.79 | 31.49 | 33.19 |
| Keyword + author | 8.81 | 7.24 | 8.06 | 11.01 |
| Keyword + favorite | 22.48 | 21.39 | 22.68 | 24.13 |
| Offset 1,000 | 12.39 | 12.10 | 14.54 | 25.68 |
| No match | 0.34 | 0.10 | 0.12 | 0.16 |
| Medium keyword cursor NEXT | 12.94 | 10.52 | 10.95 | 11.89 |
| Medium keyword cursor PREV | 10.19 | 9.77 | 9.89 | 10.02 |
| Broad keyword cursor NEXT | 89.07 | 99.34 | 101.60 | 103.56 |
| Broad keyword cursor PREV | 77.88 | 77.53 | 129.69 | 149.76 |

The initial unindexed-join implementation exceeded 3 seconds p95 even for rare matches.
The covering tag-relation index, existence predicates, index-only keyword count, native FTS rank
ordering, and production mmap setting are important to the measured performance. These are
single-reader, warm-cache results, not a claim about concurrent production traffic or cold disks.

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
