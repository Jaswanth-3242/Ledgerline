# PROMPTS.md

## 2026-11-07: Project setup and ledger schema
- Asked: how to set up LedgerLine and a first design for the ledger.
- Tool produced: setup steps, SQL schema, LedgerService.java, AuditService.java, THREATS.md draft.
- What was wrong: [what you noticed, e.g. commands were for Windows but I use a Mac; package was com.example instead of com.jash]
- What I did: [e.g. switched to Mac commands, fixed package lines, ran the app, Flyway applied the migration]
- Verified: TRUNCATE on ledger_entries is blocked by the trigger ("append-only").
- Commit: https://github.com/Jaswanth-3242/Ledgerline/commit/0e00c8d
