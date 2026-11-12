package io.spring.application;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import lombok.Value;

public class ArticleSearchCursor extends PageCursor<ArticleSearchCursor.Position> {
  private static final String PREFIX = "search-v3.";
  private static final int MAX_TOKEN_LENGTH = 16_384;

  public ArticleSearchCursor(int priority, double score, String articleId, String queryKey) {
    super(new Position(priority, score, articleId, queryKey));
    if (priority < 0
        || priority > 2
        || !Double.isFinite(score)
        || articleId == null
        || !StandardCharsets.UTF_8.newEncoder().canEncode(articleId)
        || queryKey == null
        || !queryKey.matches("[A-Za-z0-9_-]{43}")
        || encode(getData()).length() > MAX_TOKEN_LENGTH) {
      throw new IllegalArgumentException("Invalid article search cursor");
    }
  }

  public double getScore() {
    return getData().getScore();
  }

  public int getPriority() {
    return getData().getPriority();
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
    return encode(getData());
  }

  private static String encode(Position position) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String payload =
        position.getPriority()
            + "\n"
            + Double.toHexString(position.getScore())
            + "\n"
            + encoder.encodeToString(position.getArticleId().getBytes(StandardCharsets.UTF_8))
            + "\n"
            + position.getQueryKey();
    return PREFIX + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
  }

  public static ArticleSearchCursor parse(String token) {
    if (token == null) {
      return null;
    }
    try {
      if (!token.startsWith(PREFIX) || token.length() > MAX_TOKEN_LENGTH) {
        throw new IllegalArgumentException();
      }
      String payload =
          new String(
              Base64.getUrlDecoder().decode(token.substring(PREFIX.length())),
              StandardCharsets.UTF_8);
      String[] parts = payload.split("\n", -1);
      if (parts.length != 4) {
        throw new IllegalArgumentException();
      }
      String articleId =
          StandardCharsets.UTF_8
              .newDecoder()
              .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(parts[2])))
              .toString();
      return new ArticleSearchCursor(
          Integer.parseInt(parts[0]), Double.parseDouble(parts[1]), articleId, parts[3]);
    } catch (IllegalArgumentException | CharacterCodingException exception) {
      throw new IllegalArgumentException("Invalid article search cursor", exception);
    }
  }

  public static String queryKey(String tag, String author, String favoritedBy, String matchQuery) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part :
          new String[] {"field:any:asc;bm25:10,5,1;id:asc", tag, author, favoritedBy, matchQuery}) {
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
    int priority;
    double score;
    String articleId;
    String queryKey;
  }
}
