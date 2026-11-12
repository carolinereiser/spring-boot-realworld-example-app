package io.spring.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.joda.time.DateTime;

/**
 * Favorites cursors retain their ordering mode across pages. Numeric cursors resume sessions started
 * by the old API in article order; new sessions use favorite time.
 */
public class FavoriteCursor extends DateTimeCursor {
  private static final String PREFIX = "favorite:";
  private static final String LEGACY_PREFIX = "favorite-created:";
  private final String articleId;
  private final boolean legacy;

  public FavoriteCursor(DateTime favoritedAt, String articleId) {
    this(favoritedAt, articleId, false);
  }

  private FavoriteCursor(DateTime timestamp, String articleId, boolean legacy) {
    super(timestamp);
    this.articleId = articleId;
    this.legacy = legacy;
  }

  public static FavoriteCursor legacy(DateTime timestamp, String articleId) {
    return new FavoriteCursor(timestamp, articleId, true);
  }

  public boolean isLegacy() {
    return legacy;
  }

  public DateTime getTimestamp() {
    return getData();
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
    return (legacy ? LEGACY_PREFIX : PREFIX)
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
    boolean legacy = cursor.startsWith(LEGACY_PREFIX);
    if (!legacy && !cursor.startsWith(PREFIX)) {
      // The old API emitted updated_at, while its query ordered by created_at.
      return legacy(DateTimeCursor.parse(cursor), null);
    }
    try {
      String[] parts = cursor.substring((legacy ? LEGACY_PREFIX : PREFIX).length()).split(":", -1);
      if (parts.length != 2 || parts[1].isEmpty()) {
        throw new IllegalArgumentException();
      }
      DateTime timestamp = "null".equals(parts[0]) ? null : DateTimeCursor.parse(parts[0]);
      if (legacy && timestamp == null) {
        throw new IllegalArgumentException();
      }
      String id = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      if (id.isEmpty()) {
        throw new IllegalArgumentException();
      }
      return new FavoriteCursor(timestamp, id, legacy);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid favorites cursor", e);
    }
  }
}
