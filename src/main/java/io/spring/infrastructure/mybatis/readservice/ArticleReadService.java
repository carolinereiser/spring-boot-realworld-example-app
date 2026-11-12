package io.spring.infrastructure.mybatis.readservice;

import io.spring.application.ArticleSearchCursor;
import io.spring.application.CursorPageParameter;
import io.spring.application.Page;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleSearchResult;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ArticleReadService {
  ArticleData findById(@Param("id") String id);

  ArticleData findBySlug(@Param("slug") String slug);

  List<String> queryArticles(
      @Param("tag") String tag,
      @Param("author") String author,
      @Param("favoritedBy") String favoritedBy,
      @Param("keyword") String keyword,
      @Param("page") Page page);

  int countArticle(
      @Param("tag") String tag,
      @Param("author") String author,
      @Param("favoritedBy") String favoritedBy,
      @Param("keyword") String keyword);

  List<ArticleData> findArticles(@Param("articleIds") List<String> articleIds);

  List<ArticleData> findArticlesOfAuthors(
      @Param("authors") List<String> authors, @Param("page") Page page);

  List<ArticleData> findArticlesOfAuthorsWithCursor(
      @Param("authors") List<String> authors, @Param("page") CursorPageParameter page);

  int countFeedSize(@Param("authors") List<String> authors);

  List<String> findArticlesWithCursor(
      @Param("tag") String tag,
      @Param("author") String author,
      @Param("favoritedBy") String favoritedBy,
      @Param("keyword") String keyword,
      @Param("page") CursorPageParameter page);

  List<ArticleSearchResult> findArticlesWithSearchCursor(
      @Param("tag") String tag,
      @Param("author") String author,
      @Param("favoritedBy") String favoritedBy,
      @Param("keyword") String keyword,
      @Param("page") CursorPageParameter<ArticleSearchCursor> page);
}
