package io.spring.infrastructure.favorite;

import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.favorite.ArticleFavoriteRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({MyBatisArticleFavoriteRepository.class})
public class MyBatisArticleFavoriteRepositoryTest extends DbTestBase {
  @Autowired private ArticleFavoriteRepository articleFavoriteRepository;

  @Autowired
  private io.spring.infrastructure.mybatis.mapper.ArticleFavoriteMapper articleFavoriteMapper;

  @Autowired private DataSource dataSource;

  @Test
  public void should_assign_current_timestamp_when_favoriting_and_refavoriting() {
    ArticleFavorite favorite = new ArticleFavorite("123", "456");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    articleFavoriteRepository.save(favorite);
    Long timestamp =
        jdbc.queryForObject(
            "select favorited_at from article_favorites where article_id = '123'", Long.class);
    Assertions.assertNotNull(timestamp);
    Assertions.assertTrue(Math.abs(System.currentTimeMillis() - timestamp) < 5000);
    jdbc.update("update article_favorites set favorited_at = 1 where article_id = '123'");
    articleFavoriteRepository.remove(favorite);
    articleFavoriteRepository.save(favorite);
    Assertions.assertTrue(
        jdbc.queryForObject(
                "select favorited_at from article_favorites where article_id = '123'", Long.class)
            > 1);
  }

  @Test
  public void should_save_and_fetch_articleFavorite_success() {
    ArticleFavorite articleFavorite = new ArticleFavorite("123", "456");
    articleFavoriteRepository.save(articleFavorite);
    Assertions.assertNotNull(
        articleFavoriteMapper.find(articleFavorite.getArticleId(), articleFavorite.getUserId()));
  }

  @Test
  public void should_remove_favorite_success() {
    ArticleFavorite articleFavorite = new ArticleFavorite("123", "456");
    articleFavoriteRepository.save(articleFavorite);
    articleFavoriteRepository.remove(articleFavorite);
    Assertions.assertFalse(articleFavoriteRepository.find("123", "456").isPresent());
  }
}
