package io.spring.application.data;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ArticleSearchResult {
  private String id;
  private int priority;
  private double score;
}
