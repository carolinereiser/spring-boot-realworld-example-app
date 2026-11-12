package io.spring.application.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.spring.application.ArticleSearchCursor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArticleSearchCursorTest {
  private static final String ID = "00000000-0000-0000-0000-000000000001";
  private static final String QUERY = ArticleSearchCursor.queryKey(null, null, null, "\"quartz\"");

  @Test
  void should_round_trip_exact_score_bits_and_query_binding() {
    for (double score : new double[] {-0.00000123456789, -12.3456, -Double.MIN_VALUE, -0.0}) {
      ArticleSearchCursor cursor = new ArticleSearchCursor(score, ID, QUERY);
      ArticleSearchCursor parsed = ArticleSearchCursor.parse(cursor.toString());
      assertThat(Double.doubleToLongBits(parsed.getScore()))
          .isEqualTo(Double.doubleToLongBits(score));
      assertThat(parsed.getArticleId()).isEqualTo(ID);
      parsed.validateQuery(QUERY);
      assertThat(parsed.toString()).isEqualTo(cursor.toString());
    }
    assertThat(ArticleSearchCursor.parse(null)).isNull();
  }

  @Test
  void should_reject_malformed_versioned_or_non_finite_cursors() {
    for (String token :
        List.of(
            "",
            "123456789",
            "search-v2.abc",
            "search-v1.!",
            "search-v1." + "a".repeat(513),
            token("NaN\n" + ID + "\n" + QUERY),
            token("Infinity\n" + ID + "\n" + QUERY),
            token("-1.0\nnot-a-uuid\n" + QUERY),
            token("-1.0\n" + ID + "\ninvalid"),
            token("extra\nfields\nhere\n!"))) {
      assertThatThrownBy(() -> ArticleSearchCursor.parse(token))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void should_include_null_markers_and_field_boundaries_in_query_fingerprint() {
    assertThat(ArticleSearchCursor.queryKey(null, null, null, "query"))
        .isNotEqualTo(ArticleSearchCursor.queryKey("", null, null, "query"));
    assertThat(ArticleSearchCursor.queryKey("ab", "c", null, "query"))
        .isNotEqualTo(ArticleSearchCursor.queryKey("a", "bc", null, "query"));
  }

  private String token(String payload) {
    return "search-v1."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
  }
}
