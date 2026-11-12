package io.spring.application.article;

import static org.assertj.core.api.Assertions.assertThat;

import io.spring.application.ArticleQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.DateTimeCursor;
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
import java.util.List;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

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
  void should_use_chronological_cursor_order_in_both_directions_with_search_and_filters() {
    favoriteRepository.save(new ArticleFavorite(titleMatch.getId(), user.getId()));
    favoriteRepository.save(new ArticleFavorite(bodyMatch.getId(), user.getId()));
    CursorPager<ArticleData> first = cursor(null, Direction.NEXT);
    assertThat(first.getData()).extracting(ArticleData::getId).containsExactly(bodyMatch.getId());
    assertThat(first.hasNext()).isTrue();

    CursorPager<ArticleData> second =
        cursor(DateTimeCursor.parse(first.getEndCursor().toString()), Direction.NEXT);
    assertThat(second.getData()).extracting(ArticleData::getId).containsExactly(titleMatch.getId());
    assertThat(second.hasNext()).isFalse();
    assertThat(
            cursor(DateTimeCursor.parse(second.getEndCursor().toString()), Direction.NEXT)
                .getData())
        .isEmpty();

    CursorPager<ArticleData> previous =
        cursor(DateTimeCursor.parse(second.getStartCursor().toString()), Direction.PREV);
    assertThat(previous.getData())
        .extracting(ArticleData::getId)
        .containsExactly(bodyMatch.getId());
    assertThat(previous.hasPrevious()).isFalse();
    CursorPager<ArticleData> last = cursor(null, Direction.PREV);
    assertThat(last.getData()).extracting(ArticleData::getId).containsExactly(titleMatch.getId());
    assertThat(last.hasPrevious()).isTrue();
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

  private ArticleDataList search(String keyword, Page page) {
    return queryService.findRecentArticles(null, null, null, keyword, page, null);
  }

  private CursorPager<ArticleData> cursor(DateTime date, Direction direction) {
    return queryService.findRecentArticlesWithCursor(
        "sqlite",
        user.getUsername(),
        user.getUsername(),
        "quartz",
        new CursorPageParameter<>(date, 1, direction),
        null);
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
