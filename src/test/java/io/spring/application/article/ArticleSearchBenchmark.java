package io.spring.application.article;

import static org.assertj.core.api.Assertions.assertThat;

import io.spring.application.ArticleQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager.Direction;
import io.spring.application.Page;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    properties = {
      "logging.level.io.spring.infrastructure.mybatis.readservice.ArticleReadService=OFF",
      "logging.level.io.spring.infrastructure.mybatis.mapper=OFF",
      "logging.level.org.springframework=WARN"
    })
class ArticleSearchBenchmark {
  private static final int ARTICLES = 100_000;
  private static final int WARMUPS = 10;
  private static final int SAMPLES = 50;
  private static final DateTime START = new DateTime(2025, 1, 1, 0, 0);
  @TempDir static Path directory;

  @Autowired private DataSource dataSource;
  @Autowired private ArticleQueryService queryService;
  private final List<String> slowQueries = new ArrayList<>();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + directory.resolve("benchmark.db"));
  }

  @Test
  void benchmark_search_pipeline() throws Exception {
    seed();
    System.out.printf(
        "BENCHMARK articles=%d warmups=%d samples=%d java=%s cpus=%d%n",
        ARTICLES,
        WARMUPS,
        SAMPLES,
        System.getProperty("java.version"),
        Runtime.getRuntime().availableProcessors());
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      try (var result = statement.executeQuery("SELECT sqlite_version()")) {
        result.next();
        System.out.println("BENCHMARK sqlite=" + result.getString(1));
      }
    }
    measure("rare keyword (100 matches)", () -> offset("quartz", null, null, null, 0, 100));
    measure("medium keyword (10K matches)", () -> offset("sqlite", null, null, null, 0, 10_000));
    measure("broad keyword (100K matches)", () -> offset("article", null, null, null, 0, ARTICLES));
    measure("multiple keywords", () -> offset("quartz sqlite", null, null, null, 0, 100));
    measure("keyword plus tag", () -> offset("sqlite", "topic-0", null, null, 0, 10_000));
    measure("keyword plus author", () -> offset("sqlite", null, "author-0", null, 0, 1_000));
    measure("keyword plus favorite", () -> offset("sqlite", null, null, "author-0", 0, 10_000));
    measure("offset 1000", () -> offset("sqlite", null, null, null, 1_000, 10_000));
    measure("no match", () -> offset("absentword", null, null, null, 0, 0));
    measure(
        "cursor NEXT",
        () ->
            assertThat(
                    queryService
                        .findRecentArticlesWithCursor(
                            null,
                            null,
                            null,
                            "sqlite",
                            new CursorPageParameter<>(
                                START.plusSeconds(ARTICLES / 2), 20, Direction.NEXT),
                            null)
                        .getData())
                .hasSize(20));
    measure(
        "cursor PREV",
        () ->
            assertThat(
                    queryService
                        .findRecentArticlesWithCursor(
                            null,
                            null,
                            null,
                            "sqlite",
                            new CursorPageParameter<>(
                                START.plusSeconds(ARTICLES / 2), 20, Direction.PREV),
                            null)
                        .getData())
                .hasSize(20));
    assertThat(slowQueries).as("scenarios exceeding 200ms p95").isEmpty();
  }

  private void offset(
      String keyword, String tag, String author, String favorite, int offset, int count) {
    var result =
        queryService.findRecentArticles(tag, author, favorite, keyword, new Page(offset, 20), null);
    assertThat(result.getCount()).isEqualTo(count);
    assertThat(result.getArticleDatas()).hasSize(Math.min(20, Math.max(0, count - offset)));
  }

  private void measure(String name, Runnable query) {
    long coldStart = System.nanoTime();
    query.run();
    double first = (System.nanoTime() - coldStart) / 1_000_000.0;
    for (int i = 0; i < WARMUPS; i++) {
      query.run();
    }
    double[] milliseconds = new double[SAMPLES];
    for (int i = 0; i < SAMPLES; i++) {
      long start = System.nanoTime();
      query.run();
      milliseconds[i] = (System.nanoTime() - start) / 1_000_000.0;
    }
    Arrays.sort(milliseconds);
    double p95 = milliseconds[(int) Math.ceil(SAMPLES * 0.95) - 1];
    System.out.printf(
        "BENCHMARK %-36s first=%8.2f p50=%8.2f p95=%8.2f max=%8.2f ms%n",
        name, first, milliseconds[SAMPLES / 2], p95, milliseconds[SAMPLES - 1]);
    if (p95 >= 200.0) {
      slowQueries.add(name + ": " + p95 + "ms");
    }
  }

  private void seed() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement users =
              connection.prepareStatement("INSERT INTO users(id, username) VALUES (?, ?)");
          PreparedStatement tags =
              connection.prepareStatement("INSERT INTO tags(id, name) VALUES (?, ?)");
          PreparedStatement articles =
              connection.prepareStatement(
                  "INSERT INTO articles"
                      + "(id, user_id, slug, title, description, body, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)");
          PreparedStatement articleTags =
              connection.prepareStatement(
                  "INSERT INTO article_tags(article_id, tag_id) VALUES (?,?)");
          PreparedStatement favorites =
              connection.prepareStatement(
                  "INSERT INTO article_favorites(article_id, user_id) VALUES (?,?)")) {
        for (int i = 0; i < 100; i++) {
          users.setString(1, "user-" + i);
          users.setString(2, "author-" + i);
          users.addBatch();
        }
        users.executeBatch();
        for (int i = 0; i < 10; i++) {
          tags.setString(1, "tag-" + i);
          tags.setString(2, "topic-" + i);
          tags.addBatch();
        }
        tags.executeBatch();
        for (int i = 0; i < ARTICLES; i++) {
          String id =
              UUID.nameUUIDFromBytes(
                      ("article-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                  .toString();
          articles.setString(1, id);
          articles.setString(2, "user-" + i % 100);
          articles.setString(3, "article-" + i);
          articles.setString(4, "Article " + i + (i % 1000 == 0 ? " quartz" : ""));
          articles.setString(
              5, "Guide to persistence and services" + (i % 10 == 0 ? " sqlite" : ""));
          articles.setString(
              6, "Database transactions indexes testing and application design. ".repeat(20));
          articles.setLong(7, START.plusSeconds(i).getMillis());
          articles.setLong(8, START.plusSeconds(i).getMillis());
          articles.addBatch();
          for (int tag : new int[] {i % 10, (i + 1) % 10}) {
            articleTags.setString(1, id);
            articleTags.setString(2, "tag-" + tag);
            articleTags.addBatch();
          }
          if (i % 5 == 0) {
            favorites.setString(1, id);
            favorites.setString(2, "user-0");
            favorites.addBatch();
          }
          if ((i + 1) % 1000 == 0) {
            articles.executeBatch();
            articleTags.executeBatch();
            favorites.executeBatch();
          }
        }
      }
      connection.commit();
      try (Statement statement = connection.createStatement()) {
        statement.execute("ANALYZE");
      }
    }
  }
}
