# LedgerLine (BalanceVault): money movement with a ledger you can prove

Live demo: REPLACE_WITH_LIVE_URL
(Hosted on a free tier: the first request after idle can take about a minute to wake up.)

## What it is
A Java Spring Boot + PostgreSQL service where money moves through a double-entry ledger, the same request never pays twice, and every attempt (allowed or denied) leaves an audit row. It serves a small web UI at `/` and a JSON API under `/api`.

## Demo accounts (live site and local)
Password for all four: REPLACE_WITH_SEED_PASSWORD

| Username | Role | Can do |
|---|---|---|
| admin | ADMIN | approve account requests, disable/enable users, read audit and reconciliation |
| auditor | AUDITOR | read-only: audit log and reconciliation |
| alice, bob | HOLDER | deposit, withdraw, transfer on their own accounts only |

Anyone can also register at `/` (always created as HOLDER), request an account, and wait for the admin to approve it.

## How money safety works (technical write-up)
- **Double entry.** Every deposit/withdrawal/transfer writes signed ledger entries that sum to zero. Deposits and withdrawals are against a SYSTEM CASH account. A deferred database constraint trigger rejects any transaction whose entries do not sum to zero.
- **Append-only history.** Database triggers reject UPDATE, DELETE and TRUNCATE on `ledger_entries`, `transactions` and `audit_log`.
- **No overdrafts.** The balance is checked after the account rows are locked, and `CHECK (balance >= 0)` is a database backstop for customer accounts.
- **Concurrency.** Accounts are locked with `SELECT ... FOR UPDATE` in ascending id order, so parallel opposite transfers cannot deadlock and parallel withdrawals cannot overdraw.
- **Idempotency.** Money endpoints need an `Idempotency-Key` header. The key is stored with a hash of the request under a unique (user, key). Same key and same request returns the original transaction. Same key and a different request returns 409.
- **Authorisation.** Roles are checked on every endpoint. Account ownership is checked inside the posting code after locking. Someone else's account gives the same 404 as a missing account. The acting user always comes from the login, never from the request body.
- **Audit trail.** Successful operations write their audit row in the same transaction as the ledger rows. Denied attempts (including 403s) are written after the rollback.
- **Reconciliation.** `GET /api/reconcile` checks that all entries sum to zero and every balance equals the sum of its entries.
- **Rate limiting (Redis).** 5 failed logins per IP+username per 15 min (then 429), 5 sign-ups per 10 min per IP, 60 money requests per minute per IP. If Redis is unreachable the limiter fails open and logs a warning; ledger safety does not depend on Redis.
- **First admin.** If `ADMIN_BOOTSTRAP_USERNAME` and `ADMIN_BOOTSTRAP_PASSWORD` are set and no admin exists, one is created at startup (password from the environment only). `SEED_DEMO=false` turns off the demo users.
- **Database roles.** `V3` and `db/` contain a limited `ledgerline_app` role that can read/insert history but not update or delete it. Where the hosting provider does not let us create roles, the app runs as the database owner instead (see limitations).
- **Health.** `/api/health` (process up) and `/api/ready` (database reachable and ledger system account present, else 503).

## API (all JSON; HTTP Basic auth unless noted)
| Method and path | Who | Notes |
|---|---|---|
| GET /api/health, GET /api/ready | public | |
| POST /api/auth/register | public | `{username, password}` (password 10-72 printable chars); role is always HOLDER |
| GET /api/auth/me | any user | |
| GET /api/accounts, GET /api/accounts/{id}, GET /api/accounts/{id}/statement | holder: own only; admin/auditor: all | other people's accounts return 404 |
| POST /api/onboarding, GET /api/onboarding | holder | request an account, list my requests |
| POST /api/accounts/{id}/deposit, POST /api/accounts/{id}/withdraw | holder (owner) | header `Idempotency-Key`, body `{amount}` in paise |
| POST /api/transfers | holder (owner of source) | header `Idempotency-Key`, body `{fromAccountId, toAccountId, amount}` |
| GET /api/admin/users, POST /api/admin/users/{id}/status | admin | `{status: ACTIVE or DISABLED}` |
| GET /api/admin/onboarding, POST /api/admin/onboarding/{id}/decision | admin | `{approve: true or false}` |
| POST /api/accounts | admin | `{ownerId, name}` |
| GET /api/audit, GET /api/reconcile | admin, auditor | |

Errors: 400 bad input, 401 not logged in, 403 wrong role, 404 not found or not yours, 409 conflict (idempotency or duplicate), 422 insufficient funds, 429 rate limited.

Example:
```
curl -u alice:PASSWORD -X POST https://HOST/api/accounts/2/deposit \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: k1' -d '{"amount":50000}'
```

## Run locally
Needs Java 21, PostgreSQL 16, Redis.
```
psql postgres -c "CREATE USER ledgerline_user WITH PASSWORD 'CHOOSE';"
psql postgres -c "CREATE DATABASE ledgerline_db OWNER ledgerline_user;"
brew services start redis
cd backend/ledgerline
export DB_PASSWORD='CHOOSE'
./mvnw spring-boot:run        # http://localhost:8080
./mvnw test                   # needs Postgres and Redis running
```
Environment variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `FLYWAY_USER`, `FLYWAY_PASSWORD`, `REDIS_URL`, `SEED_PASSWORD`, `SEED_DEMO`, `ADMIN_BOOTSTRAP_USERNAME`, `ADMIN_BOOTSTRAP_PASSWORD`, `APP_TRUST_PROXY`, `APP_RATELIMIT_ENABLED`.

## Tests
`LedgerIntegrationTest`: parallel overdraw, replayed keys, opposite transfers, ownership, bad amounts, append-only, unbalanced entries, reconciliation, roles. `OnboardingIntegrationTest`: sign-up, onboarding flow, disabled users, health. `RateLimitIntegrationTest`: sign-up, lockout and money limits (needs Redis). `DatabaseRoleTest`: runs only when connected as `ledgerline_app`.

## Honest limitations
- Authentication is HTTP Basic over HTTPS (no tokens, no 2FA, no password reset).
- On the hosted database the app may run as the database owner because the provider may not allow creating a limited role. The append-only triggers still protect the ledger, but the extra limited-login layer is only demonstrated locally.
- The login lockout is per IP and username; a distributed attack from many IPs is not covered.
- The UI is deliberately minimal. Free hosting cold-starts after idle time.
- Amounts are in paise (integers); there is a single currency.

## AI use
Most of the backend code and tests were generated with Claude (Anthropic) and reviewed, run and tested by me; ChatGPT was used for early setup help. See PROMPTS.md for dated entries, including mistakes the tools made and how they were fixed.
