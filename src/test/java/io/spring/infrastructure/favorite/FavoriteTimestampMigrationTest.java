package io.spring.infrastructure.favorite;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FavoriteTimestampMigrationTest {
  @TempDir Path directory;

  @Test
  void upgradingPopulatedDatabasePreservesRowsAndAddsNullableCurrentTimeDefault() throws Exception {
    String url = "jdbc:sqlite:" + directory.resolve("migration.db");
    Flyway.configure().dataSource(url, "", "").target("1").load().migrate();
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "insert into article_favorites (article_id, user_id) values ('old', 'fan'), ('other', 'fan')");
    }
    Flyway.configure().dataSource(url, "", "").load().migrate();
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      try (ResultSet rows =
          statement.executeQuery(
              "select count(*) from article_favorites where favorited_at is null")) {
        assertTrue(rows.next());
        assertEquals(2, rows.getInt(1));
      }
      assertThrows(
          SQLException.class,
          () ->
              statement.executeUpdate(
                  "insert into article_favorites (article_id, user_id) values ('old', 'fan')"));
      statement.executeUpdate(
          "insert into article_favorites (article_id, user_id) values ('new', 'fan')");
      try (ResultSet rows =
          statement.executeQuery(
              "select favorited_at from article_favorites where article_id = 'new'")) {
        assertTrue(rows.next());
        assertNotNull(rows.getTimestamp(1));
        assertTrue(Math.abs(System.currentTimeMillis() - rows.getTimestamp(1).getTime()) < 5000);
      }
      statement.executeUpdate(
          "insert into article_favorites (article_id, user_id, favorited_at) values ('undated', 'fan', null)");
      statement.executeUpdate("delete from article_favorites where article_id = 'old'");
      statement.executeUpdate(
          "insert into article_favorites (article_id, user_id) values ('old', 'fan')");
      try (ResultSet rows =
          statement.executeQuery(
              "select favorited_at from article_favorites where article_id = 'old'")) {
        assertTrue(rows.next());
        assertNotNull(rows.getTimestamp(1));
      }
    }
    // The migrated schema also remains valid and the migration is not applied twice.
    Flyway.configure().dataSource(url, "", "").load().migrate();
  }
}
