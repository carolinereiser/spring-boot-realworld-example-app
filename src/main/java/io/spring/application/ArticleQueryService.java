package io.spring.application;

import static java.util.stream.Collectors.toList;

import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.data.ArticleSearchResult;
import io.spring.core.user.User;
import io.spring.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import io.spring.infrastructure.mybatis.readservice.ArticleReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class ArticleQueryService {
  public static final int MAX_KEYWORD_LENGTH = 4096;
  public static final int MAX_KEYWORD_TERMS = 64;
  private ArticleReadService articleReadService;
  private UserRelationshipQueryService userRelationshipQueryService;
  private ArticleFavoritesReadService articleFavoritesReadService;

  public Optional<ArticleData> findById(String id, User user) {
    ArticleData articleData = articleReadService.findById(id);
    if (articleData == null) {
      return Optional.empty();
    } else {
      if (user != null) {
        fillExtraInfo(id, user, articleData);
      }
      return Optional.of(articleData);
    }
  }

  public Optional<ArticleData> findBySlug(String slug, User user) {
    ArticleData articleData = articleReadService.findBySlug(slug);
    if (articleData == null) {
      return Optional.empty();
    } else {
      if (user != null) {
        fillExtraInfo(articleData.getId(), user, articleData);
      }
      return Optional.of(articleData);
    }
  }

  public CursorPager<ArticleData> findRecentArticlesWithCursor(
      String tag,
      String author,
      String favoritedBy,
      CursorPageParameter<DateTime> page,
      User currentUser) {
    List<String> articleIds =
        articleReadService.findArticlesWithCursor(tag, author, favoritedBy, null, page);
    if (articleIds.size() == 0) {
      return new CursorPager<>(new ArrayList<>(), page.getDirection(), false);
    } else {
      boolean hasExtra = articleIds.size() > page.getLimit();
      if (hasExtra) {
        articleIds.remove(page.getLimit());
      }
      if (!page.isNext()) {
        Collections.reverse(articleIds);
      }

      List<ArticleData> articles = articleReadService.findArticles(articleIds);
      fillExtraInfo(articles, currentUser);

      return new CursorPager<>(articles, page.getDirection(), hasExtra);
    }
  }

  public CursorPager<ArticleData> findRecentArticlesWithCursor(
      String tag,
      String author,
      String favoritedBy,
      String keyword,
      CursorPageParameter<ArticleSearchCursor> page,
      User currentUser) {
    String matchQuery = toMatchQuery(keyword);
    if (matchQuery == null) {
      throw new IllegalArgumentException("Use timestamp pagination when keyword is blank");
    }
    String queryKey = ArticleSearchCursor.queryKey(tag, author, favoritedBy, matchQuery);
    if (page.getCursor() != null) {
      page.getCursor().validateQuery(queryKey);
    }
    List<ArticleSearchResult> matches =
        articleReadService.findArticlesWithSearchCursor(tag, author, favoritedBy, matchQuery, page);
    if (matches.isEmpty()) {
      return new CursorPager<>(new ArrayList<>(), page.getDirection(), false);
    }
    boolean hasExtra = matches.size() > page.getLimit();
    if (hasExtra) {
      matches.remove(page.getLimit());
    }
    if (!page.isNext()) {
      Collections.reverse(matches);
    }
    List<ArticleData> articles =
        findArticlesInOrder(matches.stream().map(ArticleSearchResult::getId).collect(toList()));
    fillExtraInfo(articles, currentUser);
    ArticleSearchResult first = matches.get(0);
    ArticleSearchResult last = matches.get(matches.size() - 1);
    return new CursorPager<>(
        articles,
        page.getDirection(),
        hasExtra,
        new ArticleSearchCursor(first.getPriority(), first.getScore(), first.getId(), queryKey),
        new ArticleSearchCursor(last.getPriority(), last.getScore(), last.getId(), queryKey));
  }

  public CursorPager<ArticleData> findUserFeedWithCursor(
      User user, CursorPageParameter<DateTime> page) {
    List<String> followdUsers = userRelationshipQueryService.followedUsers(user.getId());
    if (followdUsers.size() == 0) {
      return new CursorPager<>(new ArrayList<>(), page.getDirection(), false);
    } else {
      List<ArticleData> articles =
          articleReadService.findArticlesOfAuthorsWithCursor(followdUsers, page);
      boolean hasExtra = articles.size() > page.getLimit();
      if (hasExtra) {
        articles.remove(page.getLimit());
      }
      if (!page.isNext()) {
        Collections.reverse(articles);
      }
      fillExtraInfo(articles, user);
      return new CursorPager<>(articles, page.getDirection(), hasExtra);
    }
  }

  public ArticleDataList findRecentArticles(
      String tag, String author, String favoritedBy, Page page, User currentUser) {
    return findRecentArticles(tag, author, favoritedBy, null, page, currentUser);
  }

  public ArticleDataList findRecentArticles(
      String tag, String author, String favoritedBy, String keyword, Page page, User currentUser) {
    String matchQuery = toMatchQuery(keyword);
    List<String> articleIds =
        articleReadService.queryArticles(tag, author, favoritedBy, matchQuery, page);
    int articleCount = articleReadService.countArticle(tag, author, favoritedBy, matchQuery);
    if (articleIds.size() == 0) {
      return new ArticleDataList(new ArrayList<>(), articleCount);
    } else {
      List<ArticleData> articles = findArticlesInOrder(articleIds);
      fillExtraInfo(articles, currentUser);
      return new ArticleDataList(articles, articleCount);
    }
  }

  private List<ArticleData> findArticlesInOrder(List<String> articleIds) {
    List<ArticleData> articles = articleReadService.findArticles(articleIds);
    Map<String, Integer> positions = new HashMap<>();
    for (int i = 0; i < articleIds.size(); i++) {
      positions.put(articleIds.get(i), i);
    }
    articles.sort(Comparator.comparingInt(article -> positions.get(article.getId())));
    return articles;
  }

  private String toMatchQuery(String keyword) {
    if (keyword == null) {
      return null;
    }
    if (keyword.length() > MAX_KEYWORD_LENGTH) {
      throw new InvalidArticleKeywordException(
          "Keyword must not exceed " + MAX_KEYWORD_LENGTH + " characters");
    }
    if (keyword.indexOf('\0') >= 0) {
      throw new InvalidArticleKeywordException("Keyword must not contain NUL characters");
    }
    String[] terms =
        Arrays.stream(keyword.split("(?U)\\s+"))
            .filter(term -> !term.isEmpty())
            .toArray(String[]::new);
    if (terms.length > MAX_KEYWORD_TERMS) {
      throw new InvalidArticleKeywordException(
          "Keyword must not exceed " + MAX_KEYWORD_TERMS + " terms");
    }
    return terms.length == 0
        ? null
        : Arrays.stream(terms)
            .map(term -> "\"" + term.replace("\"", "\"\"") + "\"")
            .collect(Collectors.joining(" AND "));
  }

  public ArticleDataList findUserFeed(User user, Page page) {
    List<String> followdUsers = userRelationshipQueryService.followedUsers(user.getId());
    if (followdUsers.size() == 0) {
      return new ArticleDataList(new ArrayList<>(), 0);
    } else {
      List<ArticleData> articles = articleReadService.findArticlesOfAuthors(followdUsers, page);
      fillExtraInfo(articles, user);
      int count = articleReadService.countFeedSize(followdUsers);
      return new ArticleDataList(articles, count);
    }
  }

  private void fillExtraInfo(List<ArticleData> articles, User currentUser) {
    setFavoriteCount(articles);
    if (currentUser != null) {
      setIsFavorite(articles, currentUser);
      setIsFollowingAuthor(articles, currentUser);
    }
  }

  private void setIsFollowingAuthor(List<ArticleData> articles, User currentUser) {
    Set<String> followingAuthors =
        userRelationshipQueryService.followingAuthors(
            currentUser.getId(),
            articles.stream()
                .map(articleData1 -> articleData1.getProfileData().getId())
                .collect(toList()));
    articles.forEach(
        articleData -> {
          if (followingAuthors.contains(articleData.getProfileData().getId())) {
            articleData.getProfileData().setFollowing(true);
          }
        });
  }

  private void setFavoriteCount(List<ArticleData> articles) {
    List<ArticleFavoriteCount> favoritesCounts =
        articleFavoritesReadService.articlesFavoriteCount(
            articles.stream().map(ArticleData::getId).collect(toList()));
    Map<String, Integer> countMap = new HashMap<>();
    favoritesCounts.forEach(
        item -> {
          countMap.put(item.getId(), item.getCount());
        });
    articles.forEach(
        articleData -> articleData.setFavoritesCount(countMap.get(articleData.getId())));
  }

  private void setIsFavorite(List<ArticleData> articles, User currentUser) {
    Set<String> favoritedArticles =
        articleFavoritesReadService.userFavorites(
            articles.stream().map(articleData -> articleData.getId()).collect(toList()),
            currentUser);

    articles.forEach(
        articleData -> {
          if (favoritedArticles.contains(articleData.getId())) {
            articleData.setFavorited(true);
          }
        });
  }

  private void fillExtraInfo(String id, User user, ArticleData articleData) {
    articleData.setFavorited(articleFavoritesReadService.isUserFavorite(user.getId(), id));
    articleData.setFavoritesCount(articleFavoritesReadService.articleFavoriteCount(id));
    articleData
        .getProfileData()
        .setFollowing(
            userRelationshipQueryService.isUserFollowing(
                user.getId(), articleData.getProfileData().getId()));
  }
}
