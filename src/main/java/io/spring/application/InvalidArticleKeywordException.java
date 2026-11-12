package io.spring.application;

public class InvalidArticleKeywordException extends IllegalArgumentException {
  public InvalidArticleKeywordException(String message) {
    super(message);
  }
}
