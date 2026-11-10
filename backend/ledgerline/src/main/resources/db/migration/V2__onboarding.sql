-- Users can be disabled by an admin (takes effect on their very next request).
ALTER TABLE users ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE', 'DISABLED'));

-- A registered holder asks for an account; an admin approves or rejects.
CREATE TABLE onboarding_requests (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    status     VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by BIGINT REFERENCES users (id),
    decided_at TIMESTAMPTZ
);
-- At most one pending request per user (the database enforces it, not just the app).
CREATE UNIQUE INDEX one_pending_request_per_user ON onboarding_requests (user_id) WHERE status = 'PENDING';
