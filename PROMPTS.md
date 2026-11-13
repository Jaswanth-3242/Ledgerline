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

## 2026-11-10: Redis rate limiting, tests that would have broken each other
- Asked Claude for: rate limiting on login, registration and money endpoints using Redis.
- Tool produced: a filter that runs before authentication, fixed-window counters in Redis using one atomic Lua script (increment and expiry together), a lockout after 5 failed logins per IP and username, and a client-IP rule that trusts only the last X-Forwarded-For entry when running behind a trusted proxy.
- What was wrong or risky: the new limits would have made my existing integration tests fail (they register many users from the same IP and would hit the 5-sign-ups limit), and a counter incremented in two separate steps could be left without an expiry and block someone forever.
- What I did: switched the limiter off for the older test classes with a property, gave the rate-limit tests their own random client IPs, used the single Lua script, and made Redis outages fail open with a logged warning.
- Verified: the test run shows 27 tests, 0 failures; the rate-limit tests expect 401 five times then 429 for failed logins, and 429 on the 6th sign-up from one IP.
- Commit: https://github.com/Jaswanth-3242/Ledgerline/commits/main

## 2026-11-10: Authorisation for sign-up and onboarding (mass assignment and admin controls)
- Asked Claude for: public registration, an admin-approved account request flow and admin user management.
- Tool produced: a register endpoint that has no role field at all and always creates a HOLDER, an onboarding table with a unique index so a user can only have one pending request, admin-only endpoints for approving and for disabling users (users are reloaded from the database on every request so disabling takes effect immediately), and an audit row for every admin action.
- What I tested: registering with an extra "role":"ADMIN" field never produces an admin; a holder gets 403 on the admin queue and cannot approve; approving twice gives 409; an admin cannot disable themselves; a disabled user gets 401 on the next request.
- What was wrong: [write in your own words anything you had to fix here, or remove this line]
- Commit: https://github.com/Jaswanth-3242/Ledgerline/commits/main

## 2026-11-12: Web UI and deployment
- Asked Claude for: a minimal UI served by the same Spring Boot app, a Dockerfile, and the steps to deploy on Render with PostgreSQL and Redis.
- Tool produced: a single index.html that calls the API with HTTP Basic and escapes everything it shows, a multi-stage Dockerfile, and Render settings (JDBC URL built from the internal host, environment variables, health check on /api/ready).
- What was wrong or risky: Spring Security required login for every path, so the page itself could not load until I allowed "/" and "/index.html"; the hosting provider may not let the app create a limited database role, so the limited-role layer may not apply on the hosted database; the default demo password is visible in the public source, so the deployment overrides it with SEED_PASSWORD.
- What I did: allowed the static page in the security config, set the demo password and the other secrets as environment variables on Render instead of in code, and listed the database-role limitation in the README and the submission form instead of claiming it.
- Verified: [write what you actually checked on the live site, for example: opened the link in an incognito window, logged in as alice, did a deposit and a transfer, saw the statement, and checked the audit log and the "Books balance: YES" line as admin]
- Commit: https://github.com/Jaswanth-3242/Ledgerline/commits/main
