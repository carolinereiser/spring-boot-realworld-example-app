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
  private static final String ENCODED_ID =
      Base64.getUrlEncoder().withoutPadding().encodeToString(ID.getBytes(StandardCharsets.UTF_8));

  @Test
  void should_round_trip_exact_score_bits_and_query_binding() {
    for (double score : new double[] {-0.00000123456789, -12.3456, -Double.MIN_VALUE, -0.0}) {
      ArticleSearchCursor cursor = new ArticleSearchCursor(1, score, ID, QUERY);
      ArticleSearchCursor parsed = ArticleSearchCursor.parse(cursor.toString());
      assertThat(parsed.getPriority()).isEqualTo(1);
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
            "search-v1.abc",
            "search-v2.abc",
            "search-v4.abc",
            "search-v3.!",
            "search-v3." + "a".repeat(16_385),
            token("0\nNaN\n" + ENCODED_ID + "\n" + QUERY),
            token("0\nInfinity\n" + ENCODED_ID + "\n" + QUERY),
            token("3\n-1.0\n" + ENCODED_ID + "\n" + QUERY),
            token("-1\n-1.0\n" + ENCODED_ID + "\n" + QUERY),
            token("0\n-1.0\n!\n" + QUERY),
            token("0\n-1.0\n_w\n" + QUERY),
            token("0\n-1.0\n" + ENCODED_ID + "\ninvalid"),
            token("extra\nfields\nhere\n!"))) {
      assertThatThrownBy(() -> ArticleSearchCursor.parse(token))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void should_round_trip_opaque_legacy_article_ids_without_normalization() {
    for (String id :
        List.of(
            "uuid-before-migration",
            "42",
            "",
            "00000000-0000-0000-0000-00000000ABCD",
            "legacy/with\nnewlines\tand-é-\uD83D\uDE80",
            "\uD83D\uDE80".repeat(255))) {
      ArticleSearchCursor cursor = new ArticleSearchCursor(0, -1.25, id, QUERY);
      ArticleSearchCursor parsed = ArticleSearchCursor.parse(cursor.toString());
      assertThat(parsed.getArticleId()).isEqualTo(id);
      assertThat(parsed.getScore()).isEqualTo(-1.25);
      parsed.validateQuery(QUERY);
    }
  }

  @Test
  void should_reject_positions_that_cannot_round_trip_within_the_token_limit() {
    for (String id : new String[] {null, "\uD800", "a".repeat(16_384)}) {
      assertThatThrownBy(() -> new ArticleSearchCursor(0, -1.25, id, QUERY))
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
    return "search-v3."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
  }
}
