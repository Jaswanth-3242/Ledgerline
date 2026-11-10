-- Run as the table owner or a superuser AFTER ledgerline_app_role exists. Safe to re-run.
GRANT USAGE ON SCHEMA public TO ledgerline_app_role;
GRANT SELECT, INSERT ON ledger_entries, transactions, audit_log TO ledgerline_app_role;
GRANT SELECT, INSERT ON accounts TO ledgerline_app_role;
GRANT UPDATE (balance) ON accounts TO ledgerline_app_role;
GRANT SELECT, INSERT ON users TO ledgerline_app_role;
GRANT UPDATE (status) ON users TO ledgerline_app_role;
GRANT SELECT, INSERT ON idempotency_keys TO ledgerline_app_role;
GRANT UPDATE (txn_id) ON idempotency_keys TO ledgerline_app_role;
GRANT SELECT, INSERT ON onboarding_requests TO ledgerline_app_role;
GRANT UPDATE (status, decided_by, decided_at) ON onboarding_requests TO ledgerline_app_role;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledgerline_app_role;
