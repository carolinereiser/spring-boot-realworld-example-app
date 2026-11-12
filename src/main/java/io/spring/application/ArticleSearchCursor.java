package io.spring.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import lombok.Value;

public class ArticleSearchCursor extends PageCursor<ArticleSearchCursor.Position> {
  private static final String PREFIX = "search-v1.";

  public ArticleSearchCursor(double score, String articleId, String queryKey) {
    super(new Position(score, articleId, queryKey));
    if (!Double.isFinite(score)
        || articleId == null
        || !UUID.fromString(articleId).toString().equals(articleId)
        || queryKey == null
        || !queryKey.matches("[A-Za-z0-9_-]{43}")) {
      throw new IllegalArgumentException("Invalid article search cursor");
    }
  }

  public double getScore() {
    return getData().getScore();
  }

  public String getArticleId() {
    return getData().getArticleId();
  }

  public void validateQuery(String queryKey) {
    if (!getData().getQueryKey().equals(queryKey)) {
      throw new IllegalArgumentException("Search cursor does not match the keyword and filters");
    }
  }

  @Override
  public String toString() {
    String payload =
        Double.toHexString(getScore()) + "\n" + getArticleId() + "\n" + getData().getQueryKey();
    return PREFIX
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
  }

  public static ArticleSearchCursor parse(String token) {
    if (token == null) {
      return null;
    }
    try {
      if (!token.startsWith(PREFIX) || token.length() > 512) {
        throw new IllegalArgumentException();
      }
      String payload =
          new String(
              Base64.getUrlDecoder().decode(token.substring(PREFIX.length())),
              StandardCharsets.UTF_8);
      String[] parts = payload.split("\n", -1);
      if (parts.length != 3) {
        throw new IllegalArgumentException();
      }
      return new ArticleSearchCursor(Double.parseDouble(parts[0]), parts[1], parts[2]);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Invalid article search cursor", exception);
    }
  }

  public static String queryKey(String tag, String author, String favoritedBy, String matchQuery) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part :
          new String[] {"bm25:10,5,1;id:asc", tag, author, favoritedBy, matchQuery}) {
        byte[] bytes = part == null ? new byte[0] : part.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(part == null ? -1 : bytes.length).array());
        digest.update(bytes);
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Value
  public static class Position {
    double score;
    String articleId;
    String queryKey;
  }
}
