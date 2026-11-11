package io.spring.infrastructure.bookmark;

import static org.junit.jupiter.api.Assertions.*;

import io.spring.core.bookmark.ArticleBookmark;
import io.spring.core.bookmark.ArticleBookmarkRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.repository.MyBatisArticleBookmarkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(MyBatisArticleBookmarkRepository.class)
public class MyBatisArticleBookmarkRepositoryTest extends DbTestBase {
  @Autowired private ArticleBookmarkRepository articleBookmarkRepository;

  @Test
  public void should_save_and_fetch_bookmark() {
    ArticleBookmark bookmark = new ArticleBookmark("article", "user");
    articleBookmarkRepository.save(bookmark);
    assertEquals(bookmark, articleBookmarkRepository.find("article", "user").get());
    assertFalse(articleBookmarkRepository.find("article", "other-user").isPresent());
    assertFalse(articleBookmarkRepository.find("other-article", "user").isPresent());
  }

  @Test
  public void should_save_bookmark_idempotently() {
    ArticleBookmark bookmark = new ArticleBookmark("article", "user");
    articleBookmarkRepository.save(bookmark);
    articleBookmarkRepository.save(bookmark);
    assertEquals(bookmark, articleBookmarkRepository.find("article", "user").get());
    articleBookmarkRepository.remove(bookmark);
    assertFalse(articleBookmarkRepository.find("article", "user").isPresent());
  }

  @Test
  public void should_remove_only_current_users_bookmark_and_allow_repeated_removal() {
    ArticleBookmark bookmark = new ArticleBookmark("article", "user");
    ArticleBookmark otherBookmark = new ArticleBookmark("article", "other-user");
    articleBookmarkRepository.save(bookmark);
    articleBookmarkRepository.save(otherBookmark);
    articleBookmarkRepository.remove(bookmark);
    articleBookmarkRepository.remove(bookmark);
    assertFalse(articleBookmarkRepository.find("article", "user").isPresent());
    assertEquals(otherBookmark, articleBookmarkRepository.find("article", "other-user").get());
  }
}
