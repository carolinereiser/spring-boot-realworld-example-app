package io.spring.infrastructure.mybatis.readservice;

import io.spring.application.Page;
import io.spring.application.data.ArticleData;
import io.spring.core.user.User;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ArticleBookmarksReadService {
  boolean isUserBookmarked(@Param("userId") String userId, @Param("articleId") String articleId);

  Set<String> userBookmarks(@Param("userId") String userId);

  List<ArticleData> findBookmarkedArticles(
      @Param("currentUser") User currentUser, @Param("page") Page page);

  int countUserBookmarks(@Param("currentUser") User currentUser);
}
