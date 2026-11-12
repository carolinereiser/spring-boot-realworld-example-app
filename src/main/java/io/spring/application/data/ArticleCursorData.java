package io.spring.application.data;

import lombok.Data;
import org.joda.time.DateTime;

/** Selection metadata retained while the full articles are fetched by ID. */
@Data
public class ArticleCursorData {
  private String id;
  private DateTime favoritedAt;
}
