package io.spring.api;

import io.spring.api.exception.ResourceNotFoundException;
import io.spring.application.ArticleQueryService;
import io.spring.application.Page;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.bookmark.ArticleBookmark;
import io.spring.core.bookmark.ArticleBookmarkRepository;
import io.spring.core.user.User;
import java.util.Collections;
import java.util.Map;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/articles")
@AllArgsConstructor
public class ArticleBookmarkApi {
  private ArticleBookmarkRepository articleBookmarkRepository;
  private ArticleRepository articleRepository;
  private ArticleQueryService articleQueryService;

  @PostMapping(path = "{slug}/bookmark")
  public ResponseEntity<Map<String, ArticleData>> bookmarkArticle(
      @PathVariable("slug") String slug, @AuthenticationPrincipal User user) {
    Article article =
        articleRepository.findBySlug(slug).orElseThrow(ResourceNotFoundException::new);
    articleBookmarkRepository.save(new ArticleBookmark(article.getId(), user.getId()));
    return responseArticleData(articleQueryService.findBySlug(slug, user).get());
  }

  @DeleteMapping(path = "{slug}/bookmark")
  public ResponseEntity<Map<String, ArticleData>> unbookmarkArticle(
      @PathVariable("slug") String slug, @AuthenticationPrincipal User user) {
    Article article =
        articleRepository.findBySlug(slug).orElseThrow(ResourceNotFoundException::new);
    articleBookmarkRepository
        .find(article.getId(), user.getId())
        .ifPresent(articleBookmarkRepository::remove);
    return responseArticleData(articleQueryService.findBySlug(slug, user).get());
  }

  @GetMapping(path = "bookmarked")
  public ResponseEntity<ArticleDataList> getBookmarkedArticles(
      @RequestParam(value = "offset", defaultValue = "0") int offset,
      @RequestParam(value = "limit", defaultValue = "20") int limit,
      @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(
        articleQueryService.findBookmarkedArticles(user, new Page(offset, limit)));
  }

  private ResponseEntity<Map<String, ArticleData>> responseArticleData(ArticleData articleData) {
    return ResponseEntity.ok(Collections.singletonMap("article", articleData));
  }
}
