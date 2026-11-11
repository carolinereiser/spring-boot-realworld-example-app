package io.spring.core.bookmark;

import java.util.Optional;

public interface ArticleBookmarkRepository {
  void save(ArticleBookmark articleBookmark);

  Optional<ArticleBookmark> find(String articleId, String userId);

  void remove(ArticleBookmark bookmark);
}
