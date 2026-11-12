package io.spring.application;

import java.util.List;
import lombok.Getter;

@Getter
public class CursorPager<T extends Node> {
  private List<T> data;
  private boolean next;
  private boolean previous;
  private PageCursor startCursor;
  private PageCursor endCursor;

  public CursorPager(List<T> data, Direction direction, boolean hasExtra) {
    this(data, direction, hasExtra, null, null);
  }

  public CursorPager(
      List<T> data,
      Direction direction,
      boolean hasExtra,
      PageCursor startCursor,
      PageCursor endCursor) {
    this.data = data;
    this.startCursor = startCursor;
    this.endCursor = endCursor;

    if (direction == Direction.NEXT) {
      this.previous = false;
      this.next = hasExtra;
    } else {
      this.next = false;
      this.previous = hasExtra;
    }
  }

  public boolean hasNext() {
    return next;
  }

  public boolean hasPrevious() {
    return previous;
  }

  public PageCursor getStartCursor() {
    return data.isEmpty() ? null : startCursor != null ? startCursor : data.get(0).getCursor();
  }

  public PageCursor getEndCursor() {
    return data.isEmpty()
        ? null
        : endCursor != null ? endCursor : data.get(data.size() - 1).getCursor();
  }

  public enum Direction {
    PREV,
    NEXT
  }
}
