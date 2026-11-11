package io.spring.infrastructure.repository;

import io.spring.core.bookmark.ArticleBookmark;
import io.spring.core.bookmark.ArticleBookmarkRepository;
import io.spring.infrastructure.mybatis.mapper.ArticleBookmarkMapper;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisArticleBookmarkRepository implements ArticleBookmarkRepository {
  private ArticleBookmarkMapper mapper;

  @Autowired
  public MyBatisArticleBookmarkRepository(ArticleBookmarkMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void save(ArticleBookmark articleBookmark) {
    if (mapper.find(articleBookmark.getArticleId(), articleBookmark.getUserId()) == null) {
      mapper.insert(articleBookmark);
    }
  }

  @Override
  public Optional<ArticleBookmark> find(String articleId, String userId) {
    return Optional.ofNullable(mapper.find(articleId, userId));
  }

  @Override
  public void remove(ArticleBookmark bookmark) {
    mapper.delete(bookmark);
  }
}
