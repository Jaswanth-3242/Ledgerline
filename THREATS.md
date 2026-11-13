# THREATS.md (draft, update the Status column honestly)

Status values: `done` = implemented AND covered by a passing test. `code only` = written, no test yet. `partial` = part built or tested. `todo` = not built.

| # | Threat | Mitigation | Test | Status |
|---|--------|-----------|------|--------|
| 1 | Duplicate request pays twice (client retry, double click) | `Idempotency-Key` required; `UNIQUE(user_id,key)`; stored request hash | Replay same key 10x -> 1 txn | done |
| 2 | Same key reused with a different body | Request hash compared, 409 on mismatch | Same key, different amount -> 409 | done |
| 3 | Concurrent withdrawals overdraw account | `SELECT ... FOR UPDATE`, balance checked after lock; `CHECK (balance >= 0)` backstop | 20 threads, balance covers 5 -> exactly 5 succeed | done |
| 4 | Deadlock on opposite transfers (A->B, B->A) | Locks always taken in ascending account id | 100 concurrent A->B and B->A, no failure/deadlock | done |
| 5 | IDOR: user moves money from someone else's account | Ownership checked in service layer after lock; "not yours" returns 404 same as "doesn't exist" | User A transfers from B's account -> 404 | done |
| 6 | Client supplies someone else's user id | The acting user comes from the login (AppUser principal), never from the request body; register ignores any role field | registrationNeverGrantsAnAdminRole, ownership tests | done |
| 7 | Privilege escalation (holder calls admin API, auditor writes) | Role rules at controller + service | Each role against each endpoint | done |
| 8 | Negative / zero / huge amounts, overflow | Amount 1..1e9 paise validated server-side; BIGINT minor units | -1, 0, 1e9+1, Long.MAX_VALUE -> 400 | done |
| 9 | Self-transfer to mint or confuse entries | Rejected when from == to | from == to -> 400 | done |
| 10 | Ledger tampering (UPDATE/DELETE/TRUNCATE entries) | DB triggers on `ledger_entries`, `transactions`, `audit_log` | Direct UPDATE/DELETE via SQL fails | done |
| 11 | Unbalanced transaction written | Deferred constraint trigger: entries sum to 0 per txn | Insert one-sided entry -> commit fails | done |
| 12 | Denied attempts leave no trace | `REQUIRES_NEW` audit write survives rollback | Denied transfer produces a DENIED audit row | done |
| 13 | Credential stuffing, brute force, spam sign-ups | BCrypt; Redis rate limits: 5 failed logins per IP+username per 15 min then 429, 5 sign-ups per 10 min per IP, 60 money requests per min per IP | RateLimitIntegrationTest | done |
| 14 | Stolen or forged credentials | HTTP Basic over HTTPS, user and status reloaded from the DB on every request, failed-login lockout (no tokens or 2FA built) | RateLimitIntegrationTest, disabled-user test | partial |
| 15 | Secrets in repo | Database passwords, Redis URL and demo password come from environment variables only (db/setup-roles.sql takes the password as a psql variable) | repo and git history searched for secrets before making it public | done |
| 16 | Money created or lost somewhere | /api/reconcile: all entries sum to zero and every balance equals the sum of its entries | booksBalance test | done |
| 17 | Self-promotion to admin through sign-up | Role is never read from the request; new users are always HOLDER | registrationNeverGrantsAnAdminRole | done |
| 18 | Disabled user keeps access | User row (incl. status) is loaded from the DB on every request | disabledUserCannotLogInAndAdminCannotDisableSelf | done |
| 19 | Compromised app tampers with history | Triggers block UPDATE/DELETE/TRUNCATE; limited DB role can only read/insert history (only where the host allows creating roles) | DatabaseRoleTest (when run as ledgerline_app) | partial |
| 20 | Stored XSS through names in the UI | Usernames limited to [A-Za-z0-9_]; the UI escapes every value it renders | none | code only |
| 21 | Well-known demo password in public source | Source has a default demo password for local use only; the deployment overrides it with the SEED_PASSWORD environment variable; SEED_DEMO=false removes demo users | none | partial |
