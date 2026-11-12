-- SQLite cannot add a column with a non-constant default to a populated table.
-- Store epoch milliseconds, matching the JDBC/Joda timestamp handler used by articles.
create table article_favorites_with_timestamp (
  article_id varchar(255) not null,
  user_id varchar(255) not null,
  favorited_at TIMESTAMP DEFAULT (strftime('%s', 'now') * 1000),
  primary key(article_id, user_id)
);

-- There is no historical favorite time available; keep existing rows undated.
insert into article_favorites_with_timestamp (article_id, user_id, favorited_at)
select article_id, user_id, NULL from article_favorites;

drop table article_favorites;
alter table article_favorites_with_timestamp rename to article_favorites;
