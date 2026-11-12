package io.spring.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import graphql.ExecutionResult;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FavoritePaginationIntegrationTest {
  @Autowired private DgsQueryExecutor executor;
  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void seed() {
    jdbc.update("insert into users (id, username) values ('author', 'author'), ('fan', 'fan')");
    jdbc.update(
        "insert into articles (id, user_id, slug, title, description, body, created_at, updated_at) values "
            + "('a', 'author', 'a', 'a', 'desc', 'body', 3000, 3000), "
            + "('b', 'author', 'b', 'b', 'desc', 'body', 1000, 1000), "
            + "('c', 'author', 'c', 'c', 'desc', 'body', 2000, 2000)");
    jdbc.update(
        "insert into article_favorites (article_id, user_id, favorited_at) values "
            + "('a', 'fan', null), ('b', 'fan', 10000), ('c', 'fan', 10000)");
  }

  @Test
  void restUsesFavoriteOrderAndKeepsResponseFieldsAndNonFavoritesPagination() throws Exception {
    mvc.perform(get("/articles").param("favorited", "fan").param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.articlesCount").value(3))
        .andExpect(jsonPath("$.articles[0].slug").value("c"))
        .andExpect(jsonPath("$.articles[1].slug").value("b"))
        .andExpect(jsonPath("$.articles[0].favoritedAt").doesNotExist())
        .andExpect(jsonPath("$.articles[0].favoriteCursor").doesNotExist())
        .andExpect(jsonPath("$.articles[0].favoriteQuery").doesNotExist());
    mvc.perform(get("/articles").param("favorited", "fan").param("offset", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.articles[0].slug").value("a"));
    mvc.perform(get("/articles").param("offset", "1").param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.articles[0].slug").value("c"));
  }

  @Test
  @WithAnonymousUser
  void graphqlArticlesAndProfileFavoritesAcceptCompositeAndLegacyCursors() {
    for (boolean profile : new boolean[] {false, true}) {
      Map<String, Object> first = connection(profile, "first: 1", null);
      assertEquals("c", slug(first));
      String cursor = cursor(first);
      assertTrue(cursor.startsWith("favorite:"));
      assertEquals("b", slug(connection(profile, "first: 1, after: $cursor", cursor)));
      assertEquals(
          "c",
          slug(
              connection(
                  profile,
                  "last: 1, before: $cursor",
                  cursor(connection(profile, "first: 1, after: $cursor", cursor)))));
      assertEquals("a", slug(connection(profile, "first: 1, after: $cursor", "10000")));
    }
    ExecutionResult result =
        executor.execute(
            "{ articles(first: 1, after: \"2000\") { edges { cursor node { slug } } } }");
    assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
    Map<String, Object> data = result.getData();
    Map<String, Object> connection = (Map<String, Object>) data.get("articles");
    assertEquals("b", slug(connection));
    assertEquals("1000", cursor(connection));
  }

  private Map<String, Object> connection(boolean profile, String arguments, String cursor) {
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
    ExecutionResult result =
        executor.execute(
            query,
            cursor == null ? Collections.emptyMap() : Collections.singletonMap("cursor", cursor));
    assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
    Map<String, Object> data = result.getData();
    if (profile) {
      data = (Map<String, Object>) data.get("profile");
      data = (Map<String, Object>) data.get("profile");
      return (Map<String, Object>) data.get("favorites");
    }
    return (Map<String, Object>) data.get("articles");
  }

  private Map<String, Object> edge(Map<String, Object> connection) {
    return ((List<Map<String, Object>>) connection.get("edges")).get(0);
  }

  private String slug(Map<String, Object> connection) {
    return (String) ((Map<String, Object>) edge(connection).get("node")).get("slug");
  }

  private String cursor(Map<String, Object> connection) {
    return (String) edge(connection).get("cursor");
  }
}
