CREATE VIRTUAL TABLE articles_fts USING fts5(
  title, description, body, content='articles', content_rowid='rowid'
);

INSERT INTO articles_fts(articles_fts) VALUES('rebuild');

CREATE TRIGGER articles_fts_insert AFTER INSERT ON articles BEGIN
  INSERT INTO articles_fts(rowid, title, description, body)
  VALUES (new.rowid, new.title, new.description, new.body);
END;

CREATE TRIGGER articles_fts_delete AFTER DELETE ON articles BEGIN
  INSERT INTO articles_fts(articles_fts, rowid, title, description, body)
  VALUES ('delete', old.rowid, old.title, old.description, old.body);
END;

CREATE TRIGGER articles_fts_update AFTER UPDATE ON articles BEGIN
  INSERT INTO articles_fts(articles_fts, rowid, title, description, body)
  VALUES ('delete', old.rowid, old.title, old.description, old.body);
  INSERT INTO articles_fts(rowid, title, description, body)
  VALUES (new.rowid, new.title, new.description, new.body);
END;
