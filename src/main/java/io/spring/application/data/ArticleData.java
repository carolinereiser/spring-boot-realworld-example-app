package io.spring.application.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.spring.application.DateTimeCursor;
import io.spring.application.FavoriteCursor;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.joda.time.DateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleData implements io.spring.application.Node {
  private String id;
  private String slug;
  private String title;
  private String description;
  private String body;
  private boolean favorited;
  private int favoritesCount;
  private DateTime createdAt;
  private DateTime updatedAt;
  private List<String> tagList;

  @JsonProperty("author")
  private ProfileData profileData;

  @JsonIgnore private DateTime favoritedAt;

  // A NULL timestamp still needs a favorites cursor to page through undated legacy rows.
  @JsonIgnore private boolean favoriteQuery;

  // Retain the existing constructor for callers that do not supply favorites metadata.
  public ArticleData(
      String id,
      String slug,
      String title,
      String description,
      String body,
      boolean favorited,
      int favoritesCount,
      DateTime createdAt,
      DateTime updatedAt,
      List<String> tagList,
      ProfileData profileData) {
    this(
        id,
        slug,
        title,
        description,
        body,
        favorited,
        favoritesCount,
        createdAt,
        updatedAt,
        tagList,
        profileData,
        null,
        false);
  }

  public ArticleData withFavoriteTimestamp(DateTime timestamp) {
    // Do not attach query-specific cursor state to a DTO cached by the MyBatis SqlSession.
    return new ArticleData(
        id,
        slug,
        title,
        description,
        body,
        favorited,
        favoritesCount,
        createdAt,
        updatedAt,
        tagList,
        profileData,
        timestamp,
        true);
  }

  @Override
  public DateTimeCursor getCursor() {
    if (favoriteQuery || favoritedAt != null) {
      return new FavoriteCursor(favoritedAt, id);
    }
    return new DateTimeCursor(updatedAt);
  }
}
