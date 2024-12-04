CREATE TABLE tb_user
(
    id          SERIAL PRIMARY KEY,
    username    TEXT    NOT NULL,
    login_count INTEGER NOT NULL DEFAULT 0
);
