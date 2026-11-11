package io.spring.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.JacksonCustomizations;
import io.spring.TestHelper;
import io.spring.api.security.WebSecurityConfig;
import io.spring.application.ArticleQueryService;
import io.spring.application.Page;
import io.spring.application.article.ArticleCommandService;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.bookmark.ArticleBookmark;
import io.spring.core.bookmark.ArticleBookmarkRepository;
import io.spring.core.user.User;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({ArticleBookmarkApi.class, ArticleApi.class})
@Import({WebSecurityConfig.class, JacksonCustomizations.class})
public class ArticleBookmarkApiTest extends TestWithCurrentUser {
  @Autowired private MockMvc mvc;
  @MockBean private ArticleBookmarkRepository articleBookmarkRepository;
  @MockBean private ArticleRepository articleRepository;
  @MockBean private ArticleQueryService articleQueryService;
  @MockBean private ArticleCommandService articleCommandService;
  private Article article;
  private ArticleData articleData;

  @BeforeEach
  public void setUp() throws Exception {
    super.setUp();
    RestAssuredMockMvc.mockMvc(mvc);
    article = new Article("title", "desc", "body", Arrays.asList("java"), user.getId());
    articleData = TestHelper.getArticleDataFromArticleAndUser(article, user);
    when(articleRepository.findBySlug(article.getSlug())).thenReturn(Optional.of(article));
    when(articleQueryService.findBySlug(article.getSlug(), user))
        .thenReturn(Optional.of(articleData));
  }

  @Test
  public void should_bookmark_an_article() {
    articleData.setBookmarked(true);
    given()
        .header("Authorization", "Token " + token)
        .when()
        .post("/articles/{slug}/bookmark", article.getSlug())
        .then()
        .statusCode(200)
        .body("article.id", equalTo(article.getId()))
        .body("article.bookmarked", equalTo(true));
    verify(articleBookmarkRepository).save(new ArticleBookmark(article.getId(), user.getId()));
  }

  @Test
  public void should_unbookmark_an_article() {
    ArticleBookmark bookmark = new ArticleBookmark(article.getId(), user.getId());
    when(articleBookmarkRepository.find(article.getId(), user.getId()))
        .thenReturn(Optional.of(bookmark));
    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/articles/{slug}/bookmark", article.getSlug())
        .then()
        .statusCode(200)
        .body("article.bookmarked", equalTo(false));
    verify(articleBookmarkRepository).remove(bookmark);
  }

  @Test
  public void should_succeed_when_article_was_not_bookmarked() {
    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/articles/{slug}/bookmark", article.getSlug())
        .then()
        .statusCode(200)
        .body("article.bookmarked", equalTo(false));
    verify(articleBookmarkRepository, never()).remove(any());
  }

  @Test
  public void should_return_not_found_for_missing_article() {
    given()
        .header("Authorization", "Token " + token)
        .when()
        .post("/articles/missing/bookmark")
        .then()
        .statusCode(404);
    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/articles/missing/bookmark")
        .then()
        .statusCode(404);
    verify(articleBookmarkRepository, never()).save(any());
    verify(articleBookmarkRepository, never()).remove(any());
  }

  @Test
  public void should_list_own_bookmarks_with_pagination() {
    articleData.setBookmarked(true);
    when(articleQueryService.findBookmarkedArticles(eq(user), eq(new Page(2, 5))))
        .thenReturn(new ArticleDataList(Collections.singletonList(articleData), 3));
    given()
        .header("Authorization", "Token " + token)
        .param("offset", 2)
        .param("limit", 5)
        .when()
        .get("/user/bookmarks")
        .then()
        .statusCode(200)
        .body("articlesCount", equalTo(3))
        .body("articles[0].id", equalTo(article.getId()))
        .body("articles[0].bookmarked", equalTo(true));
    verify(articleQueryService).findBookmarkedArticles(user, new Page(2, 5));
  }

  @Test
  public void should_scope_bookmarks_to_authenticated_user_even_with_user_selector_parameters() {
    User anotherUser = new User("other@test.com", "other", "123", "", "");
    when(jwtService.getSubFromToken("other-token")).thenReturn(Optional.of(anotherUser.getId()));
    when(userRepository.findById(anotherUser.getId())).thenReturn(Optional.of(anotherUser));
    when(articleQueryService.findBookmarkedArticles(eq(user), any()))
        .thenReturn(new ArticleDataList(Collections.singletonList(articleData), 1));
    when(articleQueryService.findBookmarkedArticles(eq(anotherUser), any()))
        .thenReturn(new ArticleDataList(Collections.emptyList(), 0));

    given()
        .header("Authorization", "Token " + token)
        .when()
        .get("/user/bookmarks")
        .then()
        .statusCode(200)
        .body("articlesCount", equalTo(1));
    given()
        .header("Authorization", "Token other-token")
        .param("username", user.getUsername())
        .param("userId", user.getId())
        .param("bookmarked", user.getUsername())
        .when()
        .get("/user/bookmarks")
        .then()
        .statusCode(200)
        .body("articlesCount", equalTo(0))
        .body("articles.size()", equalTo(0));
    verify(articleQueryService).findBookmarkedArticles(anotherUser, new Page());
  }

  @Test
  public void should_require_authentication_for_all_bookmark_endpoints() {
    given().when().get("/user/bookmarks").then().statusCode(401);
    given().when().get("/user/bookmarks/").then().statusCode(401);
    given().when().post("/articles/{slug}/bookmark", article.getSlug()).then().statusCode(401);
    given().when().delete("/articles/{slug}/bookmark", article.getSlug()).then().statusCode(401);
    verify(articleQueryService, never()).findBookmarkedArticles(any(), any());
    verify(articleBookmarkRepository, never()).save(any());
    verify(articleBookmarkRepository, never()).remove(any());
  }

  @Test
  public void should_list_own_bookmarks_with_trailing_slash() {
    articleData.setBookmarked(true);
    when(articleQueryService.findBookmarkedArticles(eq(user), any()))
        .thenReturn(new ArticleDataList(Collections.singletonList(articleData), 1));
    given()
        .header("Authorization", "Token " + token)
        .when()
        .get("/user/bookmarks/")
        .then()
        .statusCode(200)
        .body("articlesCount", equalTo(1))
        .body("articles[0].id", equalTo(article.getId()));
    verify(articleQueryService).findBookmarkedArticles(user, new Page());
  }

  @Test
  public void should_keep_bookmarked_article_slug_publicly_accessible() {
    Article bookmarkedArticle =
        new Article("Bookmarked", "desc", "body", Collections.emptyList(), user.getId());
    ArticleData data = TestHelper.getArticleDataFromArticleAndUser(bookmarkedArticle, user);
    when(articleQueryService.findBySlug(eq("bookmarked"), any())).thenReturn(Optional.of(data));
    given()
        .when()
        .get("/articles/bookmarked")
        .then()
        .statusCode(200)
        .body("article.slug", equalTo("bookmarked"));
    given()
        .header("Authorization", "Token " + token)
        .when()
        .get("/articles/bookmarked")
        .then()
        .statusCode(200)
        .body("article.slug", equalTo("bookmarked"));
    verify(articleQueryService, never()).findBookmarkedArticles(any(), any());
  }
}
