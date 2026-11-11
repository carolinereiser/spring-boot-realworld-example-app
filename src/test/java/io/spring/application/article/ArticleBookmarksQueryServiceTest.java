package io.spring.application.article;

import static org.junit.jupiter.api.Assertions.*;

import io.spring.application.ArticleQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager.Direction;
import io.spring.application.Page;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.bookmark.ArticleBookmark;
import io.spring.core.bookmark.ArticleBookmarkRepository;
import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.repository.MyBatisArticleBookmarkRepository;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import java.util.Arrays;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({
  ArticleQueryService.class,
  MyBatisUserRepository.class,
  MyBatisArticleRepository.class,
  MyBatisArticleBookmarkRepository.class
})
public class ArticleBookmarksQueryServiceTest extends DbTestBase {
  @Autowired private ArticleQueryService queryService;
  @Autowired private UserRepository userRepository;
  @Autowired private ArticleRepository articleRepository;
  @Autowired private ArticleBookmarkRepository bookmarkRepository;
  private User user;
  private User otherUser;
  private Article article;
  private Article olderArticle;

  @BeforeEach
  public void setUp() {
    user = new User("user@test.com", "user", "123", "", "");
    otherUser = new User("other@test.com", "other", "123", "", "");
    userRepository.save(user);
    userRepository.save(otherUser);
    article =
        new Article("new article", "desc", "body", Arrays.asList("java", "spring"), user.getId());
    olderArticle =
        new Article(
            "old article",
            "desc",
            "body",
            Arrays.asList("sql", "privacy"),
            user.getId(),
            DateTime.now().minusHours(1));
    articleRepository.save(article);
    articleRepository.save(olderArticle);
  }

  @Test
  public void should_list_only_the_current_users_bookmarks() {
    bookmarkRepository.save(new ArticleBookmark(article.getId(), user.getId()));
    bookmarkRepository.save(new ArticleBookmark(olderArticle.getId(), otherUser.getId()));
    ArticleDataList ownBookmarks = queryService.findBookmarkedArticles(user, new Page());
    ArticleDataList otherBookmarks = queryService.findBookmarkedArticles(otherUser, new Page());
    assertEquals(1, ownBookmarks.getCount());
    assertEquals(1, ownBookmarks.getArticleDatas().size());
    assertEquals(article.getId(), ownBookmarks.getArticleDatas().get(0).getId());
    assertTrue(ownBookmarks.getArticleDatas().get(0).isBookmarked());
    assertEquals(1, otherBookmarks.getCount());
    assertEquals(olderArticle.getId(), otherBookmarks.getArticleDatas().get(0).getId());

    bookmarkRepository.remove(new ArticleBookmark(article.getId(), user.getId()));
    assertEquals(0, queryService.findBookmarkedArticles(user, new Page()).getCount());
    assertTrue(queryService.findBookmarkedArticles(user, new Page()).getArticleDatas().isEmpty());
    assertEquals(1, queryService.findBookmarkedArticles(otherUser, new Page()).getCount());
  }

  @Test
  public void should_paginate_articles_without_truncating_tags_or_leaking_counts() {
    bookmarkRepository.save(new ArticleBookmark(article.getId(), user.getId()));
    bookmarkRepository.save(new ArticleBookmark(olderArticle.getId(), user.getId()));
    bookmarkRepository.save(new ArticleBookmark(article.getId(), otherUser.getId()));
    bookmarkRepository.save(new ArticleBookmark("deleted-article", user.getId()));
    ArticleDataList first = queryService.findBookmarkedArticles(user, new Page(0, 1));
    ArticleDataList second = queryService.findBookmarkedArticles(user, new Page(1, 1));
    assertEquals(2, first.getCount());
    assertEquals(1, first.getArticleDatas().size());
    assertEquals(article.getId(), first.getArticleDatas().get(0).getId());
    assertTrue(
        first.getArticleDatas().get(0).getTagList().containsAll(Arrays.asList("java", "spring")));
    assertEquals(olderArticle.getId(), second.getArticleDatas().get(0).getId());
    assertTrue(
        second.getArticleDatas().get(0).getTagList().containsAll(Arrays.asList("sql", "privacy")));
    ArticleDataList pastEnd = queryService.findBookmarkedArticles(user, new Page(2, 1));
    assertEquals(2, pastEnd.getCount());
    assertTrue(pastEnd.getArticleDatas().isEmpty());
    assertEquals(1, queryService.findBookmarkedArticles(otherUser, new Page()).getCount());
  }

  @Test
  public void should_populate_bookmarked_only_for_the_viewer_on_single_and_list_reads() {
    bookmarkRepository.save(new ArticleBookmark(article.getId(), user.getId()));
    assertTrue(queryService.findById(article.getId(), user).get().isBookmarked());
    assertTrue(queryService.findBySlug(article.getSlug(), user).get().isBookmarked());
    assertFalse(queryService.findById(article.getId(), otherUser).get().isBookmarked());
    assertFalse(queryService.findBySlug(article.getSlug(), null).get().isBookmarked());
    assertTrue(
        queryService
            .findRecentArticles(null, null, null, new Page(), user)
            .getArticleDatas()
            .get(0)
            .isBookmarked());
    assertTrue(
        queryService
            .findRecentArticles(null, null, null, new Page(), otherUser)
            .getArticleDatas()
            .stream()
            .noneMatch(ArticleData::isBookmarked));
    assertTrue(
        queryService
            .findRecentArticles(null, null, null, new Page(), null)
            .getArticleDatas()
            .stream()
            .noneMatch(ArticleData::isBookmarked));
    bookmarkRepository.remove(new ArticleBookmark(article.getId(), user.getId()));
    assertFalse(queryService.findById(article.getId(), user).get().isBookmarked());
  }

  @Test
  public void should_use_viewer_not_profile_owner_for_feed_bookmark_state() {
    userRepository.saveRelation(new FollowRelation(otherUser.getId(), user.getId()));
    bookmarkRepository.save(new ArticleBookmark(article.getId(), otherUser.getId()));
    CursorPageParameter<DateTime> page = new CursorPageParameter<>(null, 20, Direction.NEXT);
    assertTrue(
        queryService.findUserFeedWithCursor(otherUser, page).getData().get(0).isBookmarked());
    assertTrue(
        queryService.findUserFeedWithCursor(otherUser, page, user).getData().stream()
            .noneMatch(ArticleData::isBookmarked));
    assertTrue(
        queryService.findUserFeedWithCursor(otherUser, page, null).getData().stream()
            .noneMatch(ArticleData::isBookmarked));
  }

  @Test
  public void should_not_allow_anonymous_bookmark_lists() {
    assertThrows(
        NullPointerException.class, () -> queryService.findBookmarkedArticles(null, new Page()));
    assertFalse(queryService.isBookmarked(article.getId(), null));
  }
}
