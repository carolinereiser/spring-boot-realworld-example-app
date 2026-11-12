package io.spring.application;

import static org.junit.jupiter.api.Assertions.*;

import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

class FavoriteCursorTest {
  @Test
  void roundTripsTimestampAndArticleIdIncludingDelimiters() {
    FavoriteCursor original =
        new FavoriteCursor(new DateTime(123456789), "article:with/delimiters");
    FavoriteCursor parsed = FavoriteCursor.parseFavorite(original.toString());
    assertEquals(original.getFavoritedAt().getMillis(), parsed.getFavoritedAt().getMillis());
    assertEquals(original.getArticleId(), parsed.getArticleId());
    FavoriteCursor undated =
        FavoriteCursor.parseFavorite(new FavoriteCursor(null, "legacy").toString());
    assertNull(undated.getFavoritedAt());
    assertEquals("legacy", undated.getArticleId());
  }

  @Test
  void preservesOldTimestampFormatAndRejectsInvalidCursorsCleanly() {
    assertNull(FavoriteCursor.parseFavorite(null));
    assertEquals(123L, DateTimeCursor.parse("123").getMillis());
    assertEquals(123L, FavoriteCursor.parseFavorite("123").getFavoritedAt().getMillis());
    assertNull(FavoriteCursor.parseFavorite("123").getArticleId());
    assertTrue(FavoriteCursor.parseFavorite("123").isLegacy());
    FavoriteCursor legacy =
        FavoriteCursor.parseFavorite(FavoriteCursor.legacy(new DateTime(123), "id").toString());
    assertTrue(legacy.isLegacy());
    assertEquals(123, legacy.getTimestamp().getMillis());
    assertEquals("id", legacy.getArticleId());
    for (String invalid :
        new String[] {
          "",
          "favorite:",
          "favorite:no:YWJj",
          "favorite:1:!",
          "favorite:null:",
          "favorite:1:YWJj:extra",
          "favorite-created:null:YWJj",
          "favorite-created:1:"
        }) {
      assertThrows(IllegalArgumentException.class, () -> FavoriteCursor.parseFavorite(invalid));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> DateTimeCursor.parse(new FavoriteCursor(new DateTime(123), "id").toString()));
  }
}
