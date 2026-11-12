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

Keywords are case-insensitive under FTS5's default `unicode61` tokenizer. Whitespace-separated
terms are ANDed; each is quoted/escaped as literal text, so operators, wildcards, and column
selectors are not exposed as query syntax. Punctuation is handled by the tokenizer (a term such
as `spring-boot` becomes a phrase). Empty/whitespace-only input means no search filter; a
punctuation-only search returns no matches. There is no substring matching or stemming.

Offset search orders all matches by weighted BM25 ascending (lower is better), **before** applying
offset/limit. `articles_fts.rank MATCH 'bm25(10.0, 5.0, 1.0)'` maps FTS5's hidden `rank` column to
`bm25(articles_fts, 10.0, 5.0, 1.0)`. Ordering by `rank` lets FTS5 retrieve top matches natively
instead of hydrating every matching article to sort it. Equal-score matches use FTS index order,
not chronological order. Title, description, and body have weights 10, 5, and 1; BM25 also considers
term frequency and document length. The hydration step preserves ranked ID order. `articlesCount`
counts all matches after keyword, tag, author, and favorited filters, independently of the page;
unfiltered keyword counts read the FTS index without loading article contents.

The service's keyword-aware cursor overload applies the same MATCH/filter predicates but keeps
the existing timestamp ordering: newest first, using strict `<`/`>` timestamp boundaries for
NEXT/PREV. It does not rank across or within cursor pages, so timestamp cursors remain valid.
As before, articles sharing a boundary timestamp cannot be individually addressed by the
timestamp-only cursor. Existing service overloads and GraphQL callers retain their behavior;
this change adds the keyword parameter to REST, not to the GraphQL schema.

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
author, or favorite, offset 1,000, no matches, and NEXT/PREV cursor queries at the dataset midpoint.
Gradle writes the detailed output to `build/test-results/articleSearchBenchmark/` and the HTML
report to `build/reports/tests/articleSearchBenchmark/`.

#### Measured results

Session VM: Ubuntu/Linux 6.8, 8 vCPUs (Intel Xeon), 32 GB RAM, Java 11.0.33, SQLite 3.36.0
via sqlite-jdbc 3.36.0.3. Default application connection settings, including the 256 MiB mmap
limit above; no benchmark-only database tuning. All 11 scenarios passed the 200 ms p95 threshold.
Latency in milliseconds:

| Scenario | First call | p50 | p95 | Max of 50 |
| --- | ---: | ---: | ---: | ---: |
| Rare keyword, 100 matches | 78.03 | 2.14 | 3.22 | 3.50 |
| Medium keyword, 10K matches | 12.20 | 11.30 | 11.88 | 12.12 |
| Broad keyword, 100K matches | 130.05 | 103.40 | 126.28 | 137.81 |
| Multiple keywords | 1.88 | 1.56 | 1.74 | 10.18 |
| Keyword + tag | 27.35 | 19.63 | 23.41 | 26.23 |
| Keyword + author | 13.26 | 11.78 | 13.86 | 16.81 |
| Keyword + favorite | 16.20 | 15.93 | 16.35 | 17.21 |
| Offset 1,000 | 10.01 | 9.60 | 11.87 | 13.85 |
| No match | 0.35 | 0.10 | 0.14 | 0.81 |
| Cursor NEXT | 13.15 | 4.24 | 5.96 | 6.05 |
| Cursor PREV | 3.62 | 3.51 | 4.02 | 4.78 |

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
