package io.spring.api;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import graphql.ExecutionResult;
import io.spring.application.DateTimeCursor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Boot the new application against a populated V1 database, holding a cursor issued on V1. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithAnonymousUser
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FavoriteDeploymentCompatibilityTest {
  private static final List<String> oldFirstPage = new ArrayList<>();
  private static final List<String> oldPairs = new ArrayList<>();
  private static String oldCursor;

  @Autowired private DgsQueryExecutor executor;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void preDeploymentDatabase(DynamicPropertyRegistry properties) throws Exception {
    Path database = Files.createTempFile("favorites-before-deployment-", ".db");
    database.toFile().deleteOnExit();
    String url = "jdbc:sqlite:" + database;
    Flyway.configure().dataSource(url, "", "").target("1").load().migrate();
    try (Connection connection = DriverManager.getConnection(url);
        Statement sql = connection.createStatement()) {
      sql.executeUpdate(
          "insert into users (id, username) values ('author', 'author'), ('fan', 'fan'), ('other', 'other')");
      sql.executeUpdate(
          "insert into articles (id, user_id, slug, title, description, body, created_at, updated_at) values "
              + "('a', 'author', 'a', 'a', 'desc', 'body', 8000, 8000), "
              + "('b', 'author', 'b', 'b', 'desc', 'body', 7000, 7000), "
              + "('c', 'author', 'c', 'c', 'desc', 'body', 7000, 7000), "
              + "('d', 'author', 'd', 'd', 'desc', 'body', 6000, 90000), "
              + "('e', 'author', 'e', 'e', 'desc', 'body', 5000, 5000)");
      sql.executeUpdate(
          "insert into article_favorites (article_id, user_id) values "
              + "('a', 'fan'), ('b', 'fan'), ('c', 'fan'), ('d', 'fan'), ('e', 'fan'), "
              + "('a', 'other'), ('e', 'other')");
      // Match the old favorites query's created_at order and ArticleData's updated_at cursor.
      try (ResultSet rows =
          sql.executeQuery(
              "select A.id, A.updated_at from articles A "
                  + "join article_favorites AF on AF.article_id = A.id where AF.user_id = 'fan' "
                  + "order by A.created_at desc limit 2")) {
        while (rows.next()) {
          oldFirstPage.add(rows.getString(1));
          oldCursor = new DateTimeCursor(new DateTime(rows.getTimestamp(2).getTime())).toString();
        }
      }
      try (ResultSet rows =
          sql.executeQuery(
              "select article_id || ':' || user_id from article_favorites order by article_id, user_id")) {
        while (rows.next()) {
          oldPairs.add(rows.getString(1));
        }
      }
    }
    // Spring Boot/Flyway must perform the real V1 -> V2 upgrade before any new API request.
    properties.add("spring.datasource.url", () -> url);
  }

  @BeforeEach
  void addNewFavoritesAfterDeployment() {
    jdbc.update(
        "insert into articles (id, user_id, slug, title, description, body, created_at, updated_at) values "
            + "('new-1', 'author', 'new-1', 'new-1', 'desc', 'body', 4000, 4000), "
            + "('new-2', 'author', 'new-2', 'new-2', 'desc', 'body', 3000, 3000)");
    // Exercise the real database default, then force a tie even if the inserts straddle a second.
    jdbc.update(
        "insert into article_favorites (article_id, user_id) values ('new-1', 'fan'), ('new-2', 'fan')");
    jdbc.update(
        "update article_favorites set favorited_at = "
            + "(select favorited_at from article_favorites where article_id = 'new-1') where article_id = 'new-2'");
  }

  @Test
  void populatedV1DatabaseUpgradesWithoutLosingAnyFavoritePairs() {
    assertEquals("7000", oldCursor);
    assertEquals(2, oldFirstPage.size());
    assertEquals(
        oldPairs,
        jdbc.queryForList(
            "select article_id || ':' || user_id from article_favorites "
                + "where article_id not like 'new-%' order by article_id, user_id",
            String.class));
    assertEquals(
        oldPairs.size(),
        jdbc.queryForObject(
            "select count(*) from article_favorites where favorited_at is null", Integer.class));
    assertEquals(
        2,
        jdbc.queryForObject(
            "select count(*) from article_favorites where favorited_at is not null",
            Integer.class));
  }

  @Test
  void cursorIssuedBeforeDeploymentContinuesWithoutErrorsOrMissingFavorites() {
    for (boolean profile : new boolean[] {false, true}) {
      List<String> afterDeployment = walk(profile, oldCursor, true);
      List<String> allSeen = new ArrayList<>(oldFirstPage);
      allSeen.addAll(afterDeployment);
      assertEquals(new LinkedHashSet<>(favoriteIdsInArticleOrder()), new LinkedHashSet<>(allSeen));
      assertEquals(afterDeployment.size(), new LinkedHashSet<>(afterDeployment).size());
      // Old cursors have no ID: replay the boundary timestamp instead of dropping its ties.
      assertTrue(afterDeployment.contains("b"));
      assertTrue(afterDeployment.contains("c"));
      Map<String, Object> resumed = connection(profile, oldCursor, true);
      assertTrue(pageCursor(resumed, true).startsWith("favorite-created:"));
    }
  }

  @Test
  void editedArticleCursorFromOldApiCannotSkipUnseenFavorites() {
    // The fourth article was edited: old getCursor() emitted 90000 although created_at is 6000.
    String editedCursor =
        jdbc.queryForObject("select updated_at from articles where id = 'd'", String.class);
    for (boolean profile : new boolean[] {false, true}) {
      List<String> continued = walk(profile, editedCursor, true);
      assertTrue(continued.contains("e"));
      assertTrue(continued.contains("new-1"));
      assertTrue(continued.contains("new-2"));
      assertEquals(favoriteIdsInArticleOrder(), continued);
    }
  }

  @Test
  void legacyBeforeCursorConservativelyReplaysInsteadOfSkippingNewerArticles() {
    for (boolean profile : new boolean[] {false, true}) {
      assertEquals(favoriteIdsInArticleOrder(), walk(profile, oldCursor, false));
    }
  }

  @Test
  void freshSessionsReachMixedNullAndNewTiedFavoritesExactlyOnceInBothDirections()
      throws Exception {
    List<String> expected =
        jdbc.queryForList(
            "select article_id from article_favorites where user_id = 'fan' "
                + "order by favorited_at desc nulls last, article_id desc",
            String.class);
    for (boolean profile : new boolean[] {false, true}) {
      assertEquals(expected, walk(profile, null, true));
      assertEquals(expected, walk(profile, null, false));
    }
    List<String> rest = new ArrayList<>();
    for (int offset = 0; offset < expected.size(); offset += 2) {
      String response =
          mvc.perform(
                  get("/articles")
                      .param("favorited", "fan")
                      .param("limit", "2")
                      .param("offset", Integer.toString(offset)))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      JsonNode data =
          objectMapper
              .reader()
              .without(DeserializationFeature.UNWRAP_ROOT_VALUE)
              .readTree(response);
      assertEquals(expected.size(), data.get("articlesCount").asInt());
      data.get("articles").forEach(article -> rest.add(article.get("slug").asText()));
    }
    assertEquals(expected, rest);
  }

  private List<String> favoriteIdsInArticleOrder() {
    return jdbc.queryForList(
        "select A.id from articles A join article_favorites AF on A.id = AF.article_id "
            + "where AF.user_id = 'fan' order by A.created_at desc, A.id desc",
        String.class);
  }

  private List<String> walk(boolean profile, String cursor, boolean next) {
    List<String> result = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      Map<String, Object> connection = connection(profile, cursor, next);
      List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
      List<String> ids =
          edges.stream()
              .map(edge -> (String) ((Map<?, ?>) edge.get("node")).get("slug"))
              .collect(toList());
      if (next) {
        result.addAll(ids);
      } else {
        result.addAll(0, ids);
      }
      Map<String, Object> info = (Map<String, Object>) connection.get("pageInfo");
      if (!(boolean) info.get(next ? "hasNextPage" : "hasPreviousPage")) {
        return result;
      }
      assertFalse(ids.isEmpty());
      cursor = pageCursor(connection, next);
    }
    fail("Favorites traversal did not terminate");
    return result;
  }

  private String pageCursor(Map<String, Object> connection, boolean next) {
    return (String)
        ((Map<?, ?>) connection.get("pageInfo")).get(next ? "endCursor" : "startCursor");
  }

  private Map<String, Object> connection(boolean profile, String cursor, boolean next) {
    String arguments = next ? "first: 2" : "last: 2";
    if (cursor != null) {
      arguments += next ? ", after: $cursor" : ", before: $cursor";
    }
    String selection =
        "{ edges { cursor node { slug } } pageInfo { startCursor endCursor hasNextPage hasPreviousPage } }";
    String field =
        profile
            ? "profile(username: \"fan\") { profile { favorites("
                + arguments
                + ") "
                + selection
                + " } }"
            : "articles(favoritedBy: \"fan\", " + arguments + ") " + selection;
    String query =
        cursor == null ? "{ " + field + " }" : "query($cursor: String) { " + field + " }";
    ExecutionResult response =
        executor.execute(
            query,
            cursor == null ? Collections.emptyMap() : Collections.singletonMap("cursor", cursor));
    assertTrue(response.getErrors().isEmpty(), response.getErrors().toString());
    Map<String, Object> data = response.getData();
    if (profile) {
      data = (Map<String, Object>) data.get("profile");
      data = (Map<String, Object>) data.get("profile");
      return (Map<String, Object>) data.get("favorites");
    }
    return (Map<String, Object>) data.get("articles");
  }
}
