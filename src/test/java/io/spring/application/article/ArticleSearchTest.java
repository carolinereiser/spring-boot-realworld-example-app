package io.spring.application.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.spring.application.ArticleQueryService;
import io.spring.application.ArticleSearchCursor;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.DateTimeCursor;
import io.spring.application.InvalidArticleKeywordException;
import io.spring.application.Page;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.favorite.ArticleFavoriteRepository;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({
  ArticleQueryService.class,
  MyBatisUserRepository.class,
  MyBatisArticleRepository.class,
  MyBatisArticleFavoriteRepository.class
})
class ArticleSearchTest extends DbTestBase {
  @Autowired private ArticleQueryService queryService;
  @Autowired private ArticleRepository articleRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ArticleFavoriteRepository favoriteRepository;
  @Autowired private DataSource dataSource;

  private User user;
  private Article titleMatch;
  private Article descriptionMatch;
  private Article bodyMatch;

  @BeforeEach
  void seed() {
    user = new User("search@example.com", "search-author", "123", "", "");
    userRepository.save(user);
    titleMatch =
        save("Quartz guide", "sample summary", "sample content", List.of("java", "sqlite"), 3);
    descriptionMatch =
        save("Summary guide", "quartz summary", "sample content", List.of("java"), 2);
    bodyMatch = save("Content guide", "sample summary", "quartz content", List.of("sqlite"), 1);
    save("Other guide", "sample summary", "sample content", List.of("java"), 0);
  }

  @Test
  void should_search_all_fields_and_preserve_weighted_ranking_after_hydration() {
    ArticleDataList results = search("QUARTZ", new Page());
    assertThat(results.getCount()).isEqualTo(3);
    assertThat(results.getArticleDatas())
        .extracting(ArticleData::getId)
        .containsExactly(titleMatch.getId(), descriptionMatch.getId(), bodyMatch.getId());
    assertThat(results.getArticleDatas().get(0).getTagList())
        .containsExactlyInAnyOrder("java", "sqlite");
  }

  @Test
  void should_apply_keyword_tag_author_and_favorite_filters_to_results_and_count() {
    favoriteRepository.save(new ArticleFavorite(titleMatch.getId(), user.getId()));
    favoriteRepository.save(new ArticleFavorite(bodyMatch.getId(), user.getId()));

    ArticleDataList tagged =
        queryService.findRecentArticles("java", null, null, "quartz", new Page(), null);
    assertThat(tagged.getCount()).isEqualTo(2);
    assertThat(tagged.getArticleDatas())
        .extracting(ArticleData::getId)
        .containsExactly(titleMatch.getId(), descriptionMatch.getId());

    ArticleDataList combined =
        queryService.findRecentArticles(
            "java", user.getUsername(), user.getUsername(), "quartz", new Page(), user);
    assertThat(combined.getCount()).isEqualTo(1);
    assertThat(combined.getArticleDatas())
        .extracting(ArticleData::getId)
        .containsExactly(titleMatch.getId());
    assertThat(combined.getArticleDatas().get(0).isFavorited()).isTrue();
    assertThat(
            queryService
                .findRecentArticles("missing", null, null, "quartz", new Page(), null)
                .getCount())
        .isZero();
    assertThat(
            queryService
                .findRecentArticles(null, "missing", null, "quartz", new Page(), null)
                .getCount())
        .isZero();
    assertThat(
            queryService
                .findRecentArticles(null, null, "missing", "quartz", new Page(), null)
                .getCount())
        .isZero();
  }

  @Test
  void should_always_rank_title_before_description_before_body_despite_length_and_frequency() {
    Article title = save("Needle", "summary", "filler ".repeat(10_000), List.of(), 0);
    Article description = save("First", "needle ".repeat(50), "content", List.of(), 1);
    Article longDescription = save("Second", "needle", "filler ".repeat(10_000), List.of(), 2);
    Article body = save("Third", "summary", "needle ".repeat(100), List.of(), 3);
    List<String> expected =
        List.of(title.getId(), description.getId(), longDescription.getId(), body.getId());
    assertThat(ids(search("needle", new Page()).getArticleDatas()))
        .containsExactlyElementsOf(expected);
    for (Direction direction : Direction.values()) {
      ArticleSearchCursor boundary = null;
      for (int i = 0; i < expected.size(); i++) {
        int index = direction == Direction.NEXT ? i : expected.size() - i - 1;
        CursorPager<ArticleData> page = searchCursor("needle", boundary, 1, direction);
        assertThat(ids(page.getData())).containsExactly(expected.get(index));
        boundary = ArticleSearchCursor.parse(page.getStartCursor().toString());
        assertThat(ids(search("needle", new Page(index, 1)).getArticleDatas()))
            .containsExactly(expected.get(index));
      }
    }
  }

  @Test
  void should_use_the_highest_matching_field_for_cross_field_multi_term_search() {
    Article title = save("Violet", "summary", "amber", List.of(), 0);
    Article description = save("First", "violet amber", "content", List.of(), 1);
    Article body = save("Second", "summary", "violet amber", List.of(), 2);
    assertThat(ids(search("violet amber", new Page()).getArticleDatas()))
        .containsExactly(title.getId(), description.getId(), body.getId());
    assertThat(ids(searchCursor("violet amber", null, 10, Direction.NEXT).getData()))
        .containsExactly(title.getId(), description.getId(), body.getId());
  }

  @Test
  void should_paginate_legacy_text_ids_in_both_directions_without_changing_offset_order() {
    List<String> legacyIds =
        List.of("uuid-before-migration", "42", "", "legacy/with\nnewlines-é-\uD83D\uDE80");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    for (int i = 0; i < legacyIds.size(); i++) {
      Article article = save("Legacyneedle " + i, "summary", "content", List.of(), 0);
      jdbc.update("UPDATE articles SET id = ? WHERE id = ?", legacyIds.get(i), article.getId());
    }
    ArticleDataList offset = search("legacyneedle", new Page());
    List<String> expected = ids(offset.getArticleDatas());
    assertThat(offset.getCount()).isEqualTo(legacyIds.size());
    assertThat(expected).containsExactlyInAnyOrderElementsOf(legacyIds);
    for (Direction direction : Direction.values()) {
      ArticleSearchCursor boundary = null;
      for (int i = 0; i < expected.size(); i++) {
        int index = direction == Direction.NEXT ? i : expected.size() - i - 1;
        CursorPager<ArticleData> page = searchCursor("legacyneedle", boundary, 1, direction);
        assertThat(ids(page.getData())).containsExactly(expected.get(index));
        boundary = ArticleSearchCursor.parse(page.getStartCursor().toString());
      }
      assertThat(searchCursor("legacyneedle", boundary, 1, direction).getData()).isEmpty();
    }
  }

  @Test
  void should_update_and_delete_search_terms_through_repository_writes() {
    titleMatch.update("Revised guide", "revised summary", "zircon content");
    articleRepository.save(titleMatch);
    assertThat(search("quartz", new Page()).getArticleDatas())
        .extracting(ArticleData::getId)
        .containsExactly(descriptionMatch.getId(), bodyMatch.getId());
    assertThat(search("zircon", new Page()).getArticleDatas())
        .extracting(ArticleData::getId)
        .containsExactly(titleMatch.getId());

    articleRepository.remove(titleMatch);
    assertThat(search("zircon", new Page()).getCount()).isZero();
    assertThat(search("zircon", new Page()).getArticleDatas()).isEmpty();
  }

  @Test
  void should_replace_description_and_body_terms_on_update() {
    descriptionMatch.update(null, "revised summary", null);
    articleRepository.save(descriptionMatch);
    bodyMatch.update(null, null, "revised content");
    articleRepository.save(bodyMatch);
    assertThat(search("quartz", new Page()).getCount()).isEqualTo(1);
    assertThat(search("revised", new Page()).getCount()).isEqualTo(2);
  }

  @Test
  void should_paginate_ranked_matches_and_keep_total_count() {
    List<String> expected =
        List.of(titleMatch.getId(), descriptionMatch.getId(), bodyMatch.getId());
    for (int offset = 0; offset < expected.size(); offset++) {
      ArticleDataList page = search("quartz", new Page(offset, 1));
      assertThat(page.getCount()).isEqualTo(3);
      assertThat(page.getArticleDatas())
          .extracting(ArticleData::getId)
          .containsExactly(expected.get(offset));
    }
    ArticleDataList empty = search("quartz", new Page(3, 1));
    assertThat(empty.getCount()).isEqualTo(3);
    assertThat(empty.getArticleDatas()).isEmpty();
  }

  @Test
  void should_use_relevance_cursor_order_in_both_directions_with_search_and_filters() {
    favoriteRepository.save(new ArticleFavorite(titleMatch.getId(), user.getId()));
    favoriteRepository.save(new ArticleFavorite(bodyMatch.getId(), user.getId()));
    CursorPager<ArticleData> first = cursor(null, Direction.NEXT);
    assertThat(first.getData()).extracting(ArticleData::getId).containsExactly(titleMatch.getId());
    assertThat(first.getStartCursor()).isInstanceOf(ArticleSearchCursor.class);
    assertThat(first.hasNext()).isTrue();

    CursorPager<ArticleData> second =
        cursor(ArticleSearchCursor.parse(first.getEndCursor().toString()), Direction.NEXT);
    assertThat(second.getData()).extracting(ArticleData::getId).containsExactly(bodyMatch.getId());
    assertThat(second.hasNext()).isFalse();
    assertThat(
            cursor(ArticleSearchCursor.parse(second.getEndCursor().toString()), Direction.NEXT)
                .getData())
        .isEmpty();

    CursorPager<ArticleData> previous =
        cursor(ArticleSearchCursor.parse(second.getStartCursor().toString()), Direction.PREV);
    assertThat(previous.getData())
        .extracting(ArticleData::getId)
        .containsExactly(titleMatch.getId());
    assertThat(previous.hasPrevious()).isFalse();
    CursorPager<ArticleData> last = cursor(null, Direction.PREV);
    assertThat(last.getData()).extracting(ArticleData::getId).containsExactly(bodyMatch.getId());
    assertThat(last.hasPrevious()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 10})
  void should_return_identical_offset_and_cursor_pages_including_equal_scores(int size) {
    Article alpha = save("Quartz alpha", "sample summary", "sample content", List.of("sqlite"), 5);
    Article bravo = save("Quartz bravo", "sample summary", "sample content", List.of("sqlite"), 5);
    List<String> expected = ids(search("quartz", new Page()).getArticleDatas());
    List<String> tied = new ArrayList<>(List.of(titleMatch.getId(), alpha.getId(), bravo.getId()));
    tied.sort(String::compareTo);
    assertThat(expected.subList(0, 3)).containsExactlyElementsOf(tied);

    List<String> offsetIds = new ArrayList<>();
    for (int offset = 0; offset < expected.size(); offset += size) {
      offsetIds.addAll(ids(search("quartz", new Page(offset, size)).getArticleDatas()));
    }
    assertThat(offsetIds).containsExactlyElementsOf(expected);

    List<String> forward = new ArrayList<>();
    ArticleSearchCursor after = null;
    for (int offset = 0; offset < expected.size(); offset += size) {
      CursorPager<ArticleData> page = searchCursor("quartz", after, size, Direction.NEXT);
      List<String> pageIds = ids(page.getData());
      assertThat(pageIds)
          .containsExactlyElementsOf(
              expected.subList(offset, Math.min(expected.size(), offset + size)));
      assertThat(page.hasNext()).isEqualTo(offset + size < expected.size());
      forward.addAll(pageIds);
      after = ArticleSearchCursor.parse(page.getEndCursor().toString());
    }
    assertThat(forward).containsExactlyElementsOf(expected);
    assertThat(searchCursor("quartz", after, size, Direction.NEXT).getData()).isEmpty();

    List<String> backward = new ArrayList<>();
    ArticleSearchCursor before = null;
    for (int end = expected.size(); end > 0; end -= size) {
      CursorPager<ArticleData> page = searchCursor("quartz", before, size, Direction.PREV);
      assertThat(ids(page.getData()))
          .containsExactlyElementsOf(expected.subList(Math.max(0, end - size), end));
      assertThat(page.hasPrevious()).isEqualTo(end > size);
      backward.addAll(0, ids(page.getData()));
      before = ArticleSearchCursor.parse(page.getStartCursor().toString());
    }
    assertThat(backward).containsExactlyElementsOf(expected);
    assertThat(searchCursor("quartz", before, size, Direction.PREV).getData()).isEmpty();

    assertThat(queryService.findById(titleMatch.getId(), null).get().getCursor())
        .isInstanceOf(DateTimeCursor.class);
  }

  @Test
  void should_match_offset_pages_with_each_filter_and_all_filters_combined() {
    favoriteRepository.save(new ArticleFavorite(titleMatch.getId(), user.getId()));
    favoriteRepository.save(new ArticleFavorite(bodyMatch.getId(), user.getId()));
    for (String[] filters :
        List.of(
            new String[] {"sqlite", null, null},
            new String[] {null, user.getUsername(), null},
            new String[] {null, null, user.getUsername()},
            new String[] {"sqlite", user.getUsername(), user.getUsername()})) {
      List<String> expected =
          ids(
              queryService
                  .findRecentArticles(
                      filters[0], filters[1], filters[2], "quartz", new Page(), null)
                  .getArticleDatas());
      ArticleSearchCursor after = null;
      for (String id : expected) {
        CursorPager<ArticleData> page =
            queryService.findRecentArticlesWithCursor(
                filters[0],
                filters[1],
                filters[2],
                "quartz",
                new CursorPageParameter<>(after, 1, Direction.NEXT),
                null);
        assertThat(ids(page.getData())).containsExactly(id);
        after = ArticleSearchCursor.parse(page.getEndCursor().toString());
      }
    }
  }

  @Test
  void should_reject_a_cursor_reused_with_different_keyword_or_filters() {
    ArticleSearchCursor after =
        ArticleSearchCursor.parse(
            searchCursor("quartz", null, 1, Direction.NEXT).getEndCursor().toString());
    assertThatThrownBy(() -> searchCursor("guide", after, 1, Direction.NEXT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
    for (String[] filters :
        List.of(
            new String[] {"sqlite", null, null},
            new String[] {null, user.getUsername(), null},
            new String[] {null, null, user.getUsername()})) {
      assertThatThrownBy(
              () ->
                  queryService.findRecentArticlesWithCursor(
                      filters[0],
                      filters[1],
                      filters[2],
                      "quartz",
                      new CursorPageParameter<>(after, 1, Direction.NEXT),
                      null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("does not match");
    }
    assertThatThrownBy(() -> searchCursor("  ", null, 1, Direction.NEXT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timestamp");
  }

  @Test
  void should_return_empty_search_cursor_pages_for_no_matches() {
    for (Direction direction : Direction.values()) {
      CursorPager<ArticleData> page = searchCursor("absentword", null, 2, direction);
      assertThat(page.getData()).isEmpty();
      assertThat(page.getStartCursor()).isNull();
      assertThat(page.getEndCursor()).isNull();
      assertThat(page.hasNext()).isFalse();
      assertThat(page.hasPrevious()).isFalse();
    }
  }

  @Test
  void should_handle_blank_no_match_and_literal_fts_syntax_without_sql_errors() {
    assertThat(search("  \t\n", new Page()).getCount())
        .isEqualTo(search(null, new Page()).getCount());
    assertThat(search("absentword", new Page()).getCount()).isZero();
    assertThat(search(" quartz guide ", new Page()).getCount()).isEqualTo(3);
    assertThat(search("quartz OR absentword", new Page()).getCount()).isZero();
    assertThat(search("\"quartz\"", new Page()).getCount()).isEqualTo(3);
    assertThat(search("quartz*", new Page()).getCount()).isEqualTo(3);
    assertThat(search("\" * : ( ) -", new Page()).getCount()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"\u2003", "\u00a0", "\u202f", "\u0085", "\u2009"})
  void should_normalize_unicode_whitespace_for_offset_and_cursor_search(String separator) {
    String keyword = separator + "quartz" + separator + "guide" + separator;
    List<String> expected = ids(search("quartz guide", new Page()).getArticleDatas());
    ArticleDataList offset = search(keyword, new Page());
    assertThat(offset.getCount()).isEqualTo(3);
    assertThat(ids(offset.getArticleDatas())).containsExactlyElementsOf(expected);
    for (Direction direction : Direction.values()) {
      assertThat(ids(searchCursor(keyword, null, 10, direction).getData()))
          .containsExactlyElementsOf(expected);
    }

    ArticleSearchCursor after =
        ArticleSearchCursor.parse(
            searchCursor(keyword, null, 1, Direction.NEXT).getEndCursor().toString());
    assertThat(ids(searchCursor("quartz guide", after, 10, Direction.NEXT).getData()))
        .containsExactlyElementsOf(expected.subList(1, expected.size()));
    assertThat(search(separator, new Page()).getCount())
        .isEqualTo(search(null, new Page()).getCount());
    assertThatThrownBy(() -> searchCursor(separator, null, 1, Direction.NEXT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timestamp");
  }

  @Test
  void should_accept_long_queries_at_the_documented_limits() {
    assertThat(search("q".repeat(ArticleQueryService.MAX_KEYWORD_LENGTH), new Page()).getCount())
        .isZero();
    String keyword = "quartz ".repeat(ArticleQueryService.MAX_KEYWORD_TERMS);
    assertThat(search(keyword, new Page()).getCount()).isEqualTo(3);
    assertThat(searchCursor(keyword, null, 10, Direction.NEXT).getData()).hasSize(3);
  }

  @Test
  void should_reject_excessive_or_nul_containing_keywords_before_querying() {
    for (String keyword :
        List.of(
            "q".repeat(ArticleQueryService.MAX_KEYWORD_LENGTH + 1),
            "quartz ".repeat(ArticleQueryService.MAX_KEYWORD_TERMS + 1),
            "quartz\0guide")) {
      assertThatThrownBy(() -> search(keyword, new Page()))
          .isInstanceOf(InvalidArticleKeywordException.class);
      assertThatThrownBy(() -> searchCursor(keyword, null, 1, Direction.NEXT))
          .isInstanceOf(InvalidArticleKeywordException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "quartz\"",
        "NEAR(quartz,",
        "title:(quartz OR",
        "' OR 1=1 --",
        "* ^ : - ( )",
        "\"\"\""
      })
  void should_treat_malformed_fts_expressions_as_literal_text(String keyword) {
    List<String> expected = ids(search(keyword, new Page()).getArticleDatas());
    for (Direction direction : Direction.values()) {
      assertThat(ids(searchCursor(keyword, null, 10, direction).getData()))
          .containsExactlyElementsOf(expected);
    }
  }

  private ArticleDataList search(String keyword, Page page) {
    return queryService.findRecentArticles(null, null, null, keyword, page, null);
  }

  private CursorPager<ArticleData> cursor(ArticleSearchCursor cursor, Direction direction) {
    return queryService.findRecentArticlesWithCursor(
        "sqlite",
        user.getUsername(),
        user.getUsername(),
        "quartz",
        new CursorPageParameter<>(cursor, 1, direction),
        null);
  }

  private CursorPager<ArticleData> searchCursor(
      String keyword, ArticleSearchCursor cursor, int size, Direction direction) {
    return queryService.findRecentArticlesWithCursor(
        null, null, null, keyword, new CursorPageParameter<>(cursor, size, direction), null);
  }

  private List<String> ids(List<ArticleData> articles) {
    return articles.stream().map(ArticleData::getId).collect(Collectors.toList());
  }

  private Article save(
      String title, String description, String body, List<String> tags, int hoursAgo) {
    Article article =
        new Article(
            title,
            description,
            body,
            tags,
            user.getId(),
            new DateTime(2025, 1, 1, 12, 0).minusHours(hoursAgo));
    articleRepository.save(article);
    return article;
  }
}
