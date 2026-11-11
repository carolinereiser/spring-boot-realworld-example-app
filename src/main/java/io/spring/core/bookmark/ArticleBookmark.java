package io.spring.core.bookmark;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Getter
@EqualsAndHashCode
public class ArticleBookmark {
  private String articleId;
  private String userId;

  public ArticleBookmark(String articleId, String userId) {
    this.articleId = articleId;
    this.userId = userId;
  }
}
