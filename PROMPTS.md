# PROMPTS.md

## 2026-11-07: Project setup and ledger schema
- Asked: how to set up LedgerLine and a first design for the ledger.
- Tool produced: setup steps, SQL schema, LedgerService.java, AuditService.java, THREATS.md draft.
- What was wrong: [what you noticed, e.g. commands were for Windows but I use a Mac; package was com.example instead of com.jash]
- What I did: [e.g. switched to Mac commands, fixed package lines, ran the app, Flyway applied the migration]
- Verified: TRUNCATE on ledger_entries is blocked by the trigger ("append-only").
- Commit: https://github.com/Jaswanth-3242/Ledgerline/commit/0e00c8d

## 2026-11-08: Authorisation (login, roles, account ownership)
- Asked: login with three roles (admin, account holder, auditor) and endpoints where a holder can only touch their own accounts, and auditors and admins can never move money.
- Tool produced: HTTP Basic login with BCrypt, role checks with @PreAuthorize on the controllers, an ownership check inside the ledger posting code (after the account lock), and "not yours" returning the same 404 as "doesn't exist".
- What I tested: wrong password gives 401. Bob reading or withdrawing from Alice's account gives 404. The auditor depositing gives 403. Alice creating an account gives 403.
- What was wrong: the seed users get a default demo password written in the code (Demo@2026). That is fine locally but would be a hole in a deployed system, so it must be overridden with the SEED_PASSWORD environment variable at deployment.
- What I did: [write in your own words what you changed or decided, e.g. noted the default-password risk and will set SEED_PASSWORD when deploying]
- Commit: https://github.com/Jaswanth-3242/Ledgerline/commit/be9aec0d827c935e5a3844e415be7fb40bcaa837

## 2026-11-08: Audit trail and idempotency
- Asked Claude for: a record of every money attempt, including denied ones, and protection so that sending the same request twice does not pay twice.
- Tool produced: an audit_log row written in the same transaction for successful operations, and written in a separate transaction (REQUIRES_NEW) for denied ones so the record survives the rollback. Also an idempotency_keys table with a unique (user, key) and a stored hash of the request.
- What I tested myself: sending the same deposit twice with the same key returned transaction 1 both times, and the balance stayed 50000 instead of becoming 100000. Overdrawing returned 422 and a negative amount returned 400.
- What was wrong: role denials (for example the auditor's 403) are not written to the audit log, because Spring Security rejects them before the ledger code runs. Only attempts that reach the ledger are logged. Every denial should leave a trace, so this still needs a fix.
- What I did: found this gap while testing and listed it as a fix to make next.
- Commit: https://github.com/Jaswanth-3242/Ledgerline/commits/main

## 2026-11-08: Audit trail, connection pool risk found before testing
- Asked Claude for: automated tests that attack the money logic (parallel overdraws, replayed keys, opposite-direction transfers).
- Tool found: while planning the concurrency tests, Claude noticed that its own earlier code wrote the "denied" audit row from inside the failing transaction. That needs a second database connection while the first is still held, so with many failing requests at once the connection pool could run out and requests would hang.
- What I did: replaced it with a design where the money transaction finishes (commit or rollback) first, and only then the denial is written to the audit log. The success audit row still commits together with the ledger rows.
- Verified: 20 parallel withdrawals against a balance that covers 5 give exactly 5 successes and a final balance of 0. 60 parallel opposite-direction transfers finish with no deadlock and no money lost.
- Commit: https://github.com/Jaswanth-3242/Ledgerline/commits/main

## 2026-11-08: Authorisation and audit trail, 403 denials were not logged
- Asked Claude for: every denied action to leave a trace.
- Tool produced (first version): audit rows only for attempts that reached the ledger code. A role denial such as an auditor trying to deposit returned 403 but left no audit row, because Spring Security rejects it before the ledger code runs.
- What I did: added an access-denied handler that writes an ACCESS_DENIED row (who, which URL) and then returns 403.
- Verified: an automated test has an auditor try to deposit, expects 403, expects the balance unchanged, and expects exactly one ACCESS_DENIED row for that auditor. Other tests check that another user's account gives 404 and writes DENIED rows, that a holder cannot create accounts (403), that no login gives 401, and that editing or deleting ledger rows directly in SQL fails.
- Commit: https://github.com/Jaswanth-3242/Ledgerline/commits/main
