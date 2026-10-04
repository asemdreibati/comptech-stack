-- One PostgreSQL server for local development, one database per service. Each workflow service
-- keeps its engine tables and its own tables in its database, and owns its schema migrations.
create database souqly_sellers;
create database souqly_returns;
