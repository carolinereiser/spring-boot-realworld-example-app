package io.spring.infrastructure.mybatis.mapper;

import io.spring.core.bookmark.ArticleBookmark;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ArticleBookmarkMapper {
  ArticleBookmark find(@Param("articleId") String articleId, @Param("userId") String userId);

  void insert(@Param("articleBookmark") ArticleBookmark articleBookmark);

  void delete(@Param("bookmark") ArticleBookmark bookmark);
}
