-- Least privilege for the running application.
-- If the role does not exist and we are not allowed to create it (some managed Postgres hosts),
-- the grants are skipped with a notice and must be applied by the platform admin (see db/setup-roles.sql).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledgerline_app_role') THEN
        BEGIN
            CREATE ROLE ledgerline_app_role NOLOGIN;
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE NOTICE 'Cannot create ledgerline_app_role here; skipping grants. Run db/setup-roles.sql as an admin, then re-run migrations.';
        END;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledgerline_app_role') THEN
        GRANT USAGE ON SCHEMA public TO ledgerline_app_role;

        -- History tables: insert and read ONLY. No UPDATE, DELETE or TRUNCATE, ever.
        GRANT SELECT, INSERT ON ledger_entries, transactions, audit_log TO ledgerline_app_role;

        -- Accounts: only the balance may change (owner and name are fixed once created).
        GRANT SELECT, INSERT ON accounts TO ledgerline_app_role;
        GRANT UPDATE (balance) ON accounts TO ledgerline_app_role;

        -- Users: only the status may change. Roles and password hashes can never be updated by the app.
        GRANT SELECT, INSERT ON users TO ledgerline_app_role;
        GRANT UPDATE (status) ON users TO ledgerline_app_role;

        GRANT SELECT, INSERT ON idempotency_keys TO ledgerline_app_role;
        GRANT UPDATE (txn_id) ON idempotency_keys TO ledgerline_app_role;

        GRANT SELECT, INSERT ON onboarding_requests TO ledgerline_app_role;
        GRANT UPDATE (status, decided_by, decided_at) ON onboarding_requests TO ledgerline_app_role;

        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledgerline_app_role;
    END IF;
END $$;
