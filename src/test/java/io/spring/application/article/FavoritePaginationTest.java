package io.spring.application.article;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.*;

import io.spring.application.ArticleQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.DateTimeCursor;
import io.spring.application.FavoriteCursor;
import io.spring.application.Page;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.core.user.User;
import io.spring.infrastructure.DbTestBase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(ArticleQueryService.class)
class FavoritePaginationTest extends DbTestBase {
  @Autowired private ArticleQueryService service;
  @Autowired private DataSource dataSource;
  private JdbcTemplate jdbc;
  private User fan;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    fan = new User("fan@example.com", "fan", "password", "", "");
    jdbc.update("insert into users (id, username) values (?, ?)", fan.getId(), "fan");
    jdbc.update("insert into users (id, username) values ('author', 'author')");
    addArticle("a-new", 3000, 10000L);
    addArticle("b-old", 1000, 30000L);
    addArticle("c-middle", 2000, 20000L);
    addArticle("d-legacy", 4000, null);
    // A multi-tag article must still occupy only one pagination slot.
    jdbc.update("insert into tags (id, name) values ('t1', 'java'), ('t2', 'spring')");
    jdbc.update(
        "insert into article_tags (article_id, tag_id) values ('b-old', 't1'), ('b-old', 't2')");
  }

  @Test
  void restFavoritesKeepFavoriteOrderAfterRefetchAndApplyOffset() {
    ArticleDataList all = service.findRecentArticles(null, null, "fan", new Page(), null);
    assertEquals(4, all.getCount());
    assertIds(all.getArticleDatas(), "b-old", "c-middle", "a-new", "d-legacy");
    assertIds(
        service.findRecentArticles(null, null, "fan", new Page(1, 2), null).getArticleDatas(),
        "c-middle",
        "a-new");
    assertIds(
        service.findRecentArticles("java", "author", "fan", new Page(), null).getArticleDatas(),
        "b-old");
    assertTrue(
        service
            .findRecentArticles(null, null, "missing", new Page(), null)
            .getArticleDatas()
            .isEmpty());
  }

  @Test
  void favoritesCarryTimestampsAndPageInBothDirectionsAcrossNullBoundary() {
    CursorPager<ArticleData> first = favorites(null, 2, Direction.NEXT);
    assertIds(first.getData(), "b-old", "c-middle");
    assertTrue(first.hasNext());
    assertEquals(30000L, first.getData().get(0).getFavoritedAt().getMillis());
    assertEquals(
        20000L,
        FavoriteCursor.parseFavorite(first.getEndCursor().toString()).getFavoritedAt().getMillis());

    CursorPager<ArticleData> second = favorites(first.getEndCursor().toString(), 2, Direction.NEXT);
    assertIds(second.getData(), "a-new", "d-legacy");
    assertFalse(second.hasNext());
    assertNull(second.getData().get(1).getFavoritedAt());
    assertTrue(favorites(second.getEndCursor().toString(), 2, Direction.NEXT).getData().isEmpty());

    CursorPager<ArticleData> previous =
        favorites(second.getStartCursor().toString(), 2, Direction.PREV);
    assertIds(previous.getData(), "b-old", "c-middle");
    assertFalse(previous.hasPrevious());
    assertIds(
        favorites(second.getEndCursor().toString(), 2, Direction.PREV).getData(),
        "c-middle",
        "a-new");

    CursorPager<ArticleData> last = favorites(null, 2, Direction.PREV);
    assertIds(last.getData(), "a-new", "d-legacy");
    assertTrue(last.hasPrevious());
  }

  @Test
  void compositeCursorsVisitEveryTimestampTieAndNullExactlyOnce() {
    addArticle("e-tie", 5000, 20000L);
    addArticle("f-tie", 6000, 20000L);
    addArticle("z-legacy", 7000, null);
    List<String> expected =
        Arrays.asList("b-old", "f-tie", "e-tie", "c-middle", "a-new", "z-legacy", "d-legacy");
    assertEquals(expected, walk(Direction.NEXT));
    assertEquals(expected, walk(Direction.PREV));
    assertEquals(
        expected,
        service.findRecentArticles(null, null, "fan", new Page(), null).getArticleDatas().stream()
            .map(ArticleData::getId)
            .collect(toList()));
  }

  @Test
  void legacyTimestampCursorsAreStillAcceptedForFavorites() {
    assertIds(favorites("20000", 20, Direction.NEXT).getData(), "a-new", "d-legacy");
    assertIds(favorites("20000", 20, Direction.PREV).getData(), "b-old");
    assertIds(
        service
            .findRecentArticlesWithCursor(
                null,
                null,
                "fan",
                new CursorPageParameter<>(new DateTime(20000), 20, Direction.NEXT),
                null)
            .getData(),
        "a-new",
        "d-legacy");
  }

  @Test
  void nonFavoritesKeepTheirOrderingAndLegacyCursorBehaviorAfterFavoritesRead() {
    favorites(null, 20, Direction.NEXT);
    ArticleDataList all = service.findRecentArticles(null, null, null, new Page(), null);
    assertIds(all.getArticleDatas(), "d-legacy", "a-new", "c-middle", "b-old");
    assertIds(
        service.findRecentArticles(null, null, null, new Page(1, 2), null).getArticleDatas(),
        "a-new",
        "c-middle");
    CursorPager<ArticleData> next =
        service.findRecentArticlesWithCursor(
            null,
            null,
            null,
            new CursorPageParameter<>(DateTimeCursor.parse("2500"), 2, Direction.NEXT),
            null);
    assertIds(next.getData(), "c-middle", "b-old");
    CursorPager<ArticleData> previous =
        service.findRecentArticlesWithCursor(
            null,
            null,
            null,
            new CursorPageParameter<>(DateTimeCursor.parse("2500"), 2, Direction.PREV),
            null);
    assertIds(previous.getData(), "d-legacy", "a-new");
    for (ArticleData article : all.getArticleDatas()) {
      assertNull(article.getFavoritedAt());
      assertEquals(
          Long.toString(article.getUpdatedAt().getMillis()), article.getCursor().toString());
    }
  }

  @Test
  void favoriteMetadataDoesNotChangeFeedCursors() {
    favorites(null, 20, Direction.NEXT);
    jdbc.update("insert into follows (user_id, follow_id) values (?, 'author')", fan.getId());
    assertEquals(4, service.findUserFeed(fan, new Page()).getCount());
    for (ArticleData article :
        service
            .findUserFeedWithCursor(fan, new CursorPageParameter<>(null, 20, Direction.NEXT))
            .getData()) {
      assertNull(article.getFavoritedAt());
      assertEquals(
          Long.toString(article.getUpdatedAt().getMillis()), article.getCursor().toString());
    }
  }

  @Test
  void eachUsersFavoriteTimestampsStaySeparateAfterRefetch() {
    jdbc.update("insert into users (id, username) values ('other', 'other')");
    jdbc.update(
        "insert into article_favorites (article_id, user_id, favorited_at) values "
            + "('a-new', 'other', 50000), ('b-old', 'other', 1000)");
    assertIds(
        favorites(null, 20, Direction.NEXT).getData(), "b-old", "c-middle", "a-new", "d-legacy");
    CursorPager<ArticleData> other =
        service.findRecentArticlesWithCursor(
            null, null, "other", new CursorPageParameter<>(null, 20, Direction.NEXT), null);
    assertIds(other.getData(), "a-new", "b-old");
    assertEquals(50000L, other.getData().get(0).getFavoritedAt().getMillis());
    assertEquals(
        10000L, favorites(null, 20, Direction.NEXT).getData().get(2).getFavoritedAt().getMillis());
  }

  @Test
  void refavoriteGetsCurrentDatabaseTimestampAndMovesToFront() {
    jdbc.update("delete from article_favorites where article_id = 'd-legacy'");
    jdbc.update(
        "insert into article_favorites (article_id, user_id) values ('d-legacy', ?)", fan.getId());
    CursorPager<ArticleData> first = favorites(null, 1, Direction.NEXT);
    assertIds(first.getData(), "d-legacy");
    assertTrue(
        Math.abs(System.currentTimeMillis() - first.getData().get(0).getFavoritedAt().getMillis())
            < 5000);
  }

  private void addArticle(String id, long createdAt, Long favoritedAt) {
    jdbc.update(
        "insert into articles (id, user_id, slug, title, description, body, created_at, updated_at) "
            + "values (?, 'author', ?, ?, 'description', 'body', ?, ?)",
        id,
        id,
        id,
        createdAt,
        createdAt + 100000);
    jdbc.update(
        "insert into article_favorites (article_id, user_id, favorited_at) values (?, ?, ?)",
        id,
        fan.getId(),
        favoritedAt);
  }

  private CursorPager<ArticleData> favorites(String cursor, int limit, Direction direction) {
    return service.findRecentArticlesWithCursor(
        null,
        null,
        "fan",
        new CursorPageParameter<>(FavoriteCursor.parseFavorite(cursor), limit, direction),
        null);
  }

  private List<String> walk(Direction direction) {
    List<String> ids = new ArrayList<>();
    String cursor = null;
    for (int i = 0; i < 10; i++) {
      CursorPager<ArticleData> page = favorites(cursor, 1, direction);
      if (page.getData().isEmpty()) {
        return ids;
      }
      if (direction == Direction.NEXT) {
        ids.add(page.getData().get(0).getId());
        cursor = page.getEndCursor().toString();
      } else {
        ids.add(0, page.getData().get(0).getId());
        cursor = page.getStartCursor().toString();
      }
    }
    fail("Pagination did not terminate");
    return ids;
  }

  private void assertIds(List<ArticleData> articles, String... expected) {
    assertEquals(
        Arrays.asList(expected), articles.stream().map(ArticleData::getId).collect(toList()));
  }
}
