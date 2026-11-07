# THREATS.md (draft, update the Status column honestly)

Status values: `done` = implemented AND covered by a passing test. `code only` = written, no test yet. `todo` = not built.

| # | Threat | Mitigation | Test | Status |
|---|--------|-----------|------|--------|
| 1 | Duplicate request pays twice (client retry, double click) | `Idempotency-Key` required; `UNIQUE(user_id,key)`; stored request hash | Replay same key 10x -> 1 txn | code only |
| 2 | Same key reused with a different body | Request hash compared, 409 on mismatch | Same key, different amount -> 409 | code only |
| 3 | Concurrent withdrawals overdraw account | `SELECT ... FOR UPDATE`, balance checked after lock; `CHECK (balance >= 0)` backstop | 20 threads, balance covers 5 -> exactly 5 succeed | code only |
| 4 | Deadlock on opposite transfers (A->B, B->A) | Locks always taken in ascending account id | 100 concurrent A->B and B->A, no failure/deadlock | code only |
| 5 | IDOR: user moves money from someone else's account | Ownership checked in service layer after lock; "not yours" returns 404 same as "doesn't exist" | User A transfers from B's account -> 404 | code only |
| 6 | Client supplies someone else's user id | Actor id read from the auth principal, never from the request | Body with extra `userId` field is ignored | todo |
| 7 | Privilege escalation (holder calls admin API, auditor writes) | Role rules at controller + service | Each role against each endpoint | todo |
| 8 | Negative / zero / huge amounts, overflow | Amount 1..1e9 paise validated server-side; BIGINT minor units | -1, 0, 1e9+1, Long.MAX_VALUE -> 400 | code only |
| 9 | Self-transfer to mint or confuse entries | Rejected when from == to | from == to -> 400 | code only |
| 10 | Ledger tampering (UPDATE/DELETE/TRUNCATE entries) | DB triggers on `ledger_entries`, `transactions`, `audit_log` | Direct UPDATE/DELETE via SQL fails | code only |
| 11 | Unbalanced transaction written | Deferred constraint trigger: entries sum to 0 per txn | Insert one-sided entry -> commit fails | code only |
| 12 | Denied attempts leave no trace | `REQUIRES_NEW` audit write survives rollback | Denied transfer produces a DENIED audit row | code only |
| 13 | Credential stuffing / weak passwords | BCrypt, rate limiting on login | | todo |
| 14 | Stolen / forged token | Signed JWT, short expiry, server-side role lookup | | todo |
| 15 | Secrets in repo | `DB_PASSWORD` and JWT secret via env vars only | grep repo for secrets before submitting | todo |
| 16 | Money created or lost somewhere | Reconciliation endpoint: sum(all entries)=0 and each balance = sum of its entries | Run after concurrency tests | todo |
