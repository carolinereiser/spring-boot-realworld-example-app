package io.spring.infrastructure.article;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArticleSearchMigrationTest {
  @TempDir Path directory;

  @Test
  void should_backfill_existing_uuid_articles_and_keep_index_transactional() throws Exception {
    String url = "jdbc:sqlite:" + directory.resolve("migration.db");
    Flyway.configure().dataSource(url, "", "").target("1").load().migrate();
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO articles(id, slug, title, description, body, created_at) "
              + "VALUES ('uuid-before-migration', 'existing', 'quartz', 'summary', 'content', CURRENT_TIMESTAMP)");
    }
    Flyway flyway = Flyway.configure().dataSource(url, "", "").load();
    flyway.migrate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();

    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      assertThat(matchingId(statement, "quartz")).isEqualTo("uuid-before-migration");
      connection.setAutoCommit(false);
      statement.executeUpdate(
          "UPDATE articles SET title = 'zircon' WHERE id = 'uuid-before-migration'");
      assertThat(matchingId(statement, "quartz")).isNull();
      assertThat(matchingId(statement, "zircon")).isEqualTo("uuid-before-migration");
      connection.rollback();
      assertThat(matchingId(statement, "quartz")).isEqualTo("uuid-before-migration");
      assertThat(matchingId(statement, "zircon")).isNull();
      statement.executeUpdate(
          "INSERT INTO articles_fts(articles_fts, rank) VALUES('integrity-check', 1)");
    }
  }

  private String matchingId(Statement statement, String term) throws Exception {
    try (ResultSet result =
        statement.executeQuery(
            "SELECT A.id FROM articles A "
                + "JOIN articles_fts ON articles_fts.rowid = A.rowid WHERE articles_fts MATCH '"
                + term
                + "'")) {
      return result.next() ? result.getString(1) : null;
    }
  }
}
