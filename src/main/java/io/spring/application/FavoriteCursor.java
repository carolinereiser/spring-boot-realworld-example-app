package io.spring.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.joda.time.DateTime;

/** A favorites-only cursor; plain millisecond cursors remain valid for older clients. */
public class FavoriteCursor extends DateTimeCursor {
  private static final String PREFIX = "favorite:";
  private final String articleId;

  public FavoriteCursor(DateTime favoritedAt, String articleId) {
    super(favoritedAt);
    this.articleId = articleId;
  }

  public DateTime getFavoritedAt() {
    return getData();
  }

  public String getArticleId() {
    return articleId;
  }

  @Override
  public String toString() {
    if (articleId == null) {
      return super.toString();
    }
    return PREFIX
        + (getData() == null ? "null" : getData().getMillis())
        + ":"
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(articleId.getBytes(StandardCharsets.UTF_8));
  }

  public static FavoriteCursor parseFavorite(String cursor) {
    if (cursor == null) {
      return null;
    }
    if (!cursor.startsWith(PREFIX)) {
      return new FavoriteCursor(DateTimeCursor.parse(cursor), null);
    }
    try {
      String[] parts = cursor.substring(PREFIX.length()).split(":", -1);
      if (parts.length != 2 || parts[1].isEmpty()) {
        throw new IllegalArgumentException();
      }
      DateTime timestamp = "null".equals(parts[0]) ? null : DateTimeCursor.parse(parts[0]);
      String id = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      if (id.isEmpty()) {
        throw new IllegalArgumentException();
      }
      return new FavoriteCursor(timestamp, id);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid favorites cursor", e);
    }
  }
}
