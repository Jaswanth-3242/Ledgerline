-- One-time setup, run as a database superuser/admin (locally: psql postgres -v app_password='CHOOSE_ONE' -f db/setup-roles.sql)
-- 1) ledgerline_app_role = a group role with NO login. Migration V3 grants it ONLY what the app needs.
-- 2) ledgerline_app      = the login the running app uses. It is a member of the group role.
-- The owner login (the one that runs Flyway migrations) is separate and is NOT used by the running app.
CREATE ROLE ledgerline_app_role NOLOGIN;
CREATE ROLE ledgerline_app LOGIN PASSWORD :'app_password' IN ROLE ledgerline_app_role;
