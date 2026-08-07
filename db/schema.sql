CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    phone TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    password_hash TEXT,
    account_token TEXT UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS relay_state (
    id TEXT PRIMARY KEY,
    payload JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS account_token TEXT UNIQUE;

CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    user_id TEXT REFERENCES users(id),
    device_id TEXT NOT NULL,
    manufacturer TEXT,
    model TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS families (
    id TEXT PRIMARY KEY,
    pair_code TEXT UNIQUE NOT NULL,
    elder_user_id TEXT REFERENCES users(id),
    elder_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS family_members (
    id TEXT PRIMARY KEY,
    family_id TEXT REFERENCES families(id),
    user_id TEXT REFERENCES users(id),
    role TEXT NOT NULL CHECK (role IN ('elder', 'family')),
    display_name TEXT NOT NULL,
    can_receive_help BOOLEAN NOT NULL DEFAULT true,
    can_annotate BOOLEAN NOT NULL DEFAULT true,
    can_request_control BOOLEAN NOT NULL DEFAULT false,
    status TEXT NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (family_id, user_id, role)
);

CREATE TABLE IF NOT EXISTS invites (
    id TEXT PRIMARY KEY,
    family_id TEXT REFERENCES families(id),
    invite_code TEXT NOT NULL,
    created_by_user_id TEXT REFERENCES users(id),
    status TEXT NOT NULL DEFAULT 'pending',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS bind_requests (
    id TEXT PRIMARY KEY,
    family_id TEXT REFERENCES families(id),
    invite_id TEXT REFERENCES invites(id),
    requester_user_id TEXT REFERENCES users(id),
    requester_name TEXT NOT NULL,
    requester_phone TEXT,
    status TEXT NOT NULL DEFAULT 'pending',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS assist_sessions (
    id TEXT PRIMARY KEY,
    family_id TEXT REFERENCES families(id),
    elder_member_id TEXT REFERENCES family_members(id),
    helper_member_id TEXT REFERENCES family_members(id),
    status TEXT NOT NULL DEFAULT 'active',
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at TIMESTAMPTZ,
    end_reason TEXT
);

CREATE TABLE IF NOT EXISTS remote_control_grants (
    id TEXT PRIMARY KEY,
    session_id TEXT REFERENCES assist_sessions(id),
    requested_by_member_id TEXT REFERENCES family_members(id),
    decided_by_member_id TEXT REFERENCES family_members(id),
    status TEXT NOT NULL,
    reason TEXT,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id TEXT PRIMARY KEY,
    family_id TEXT REFERENCES families(id),
    session_id TEXT,
    actor_user_id TEXT,
    type TEXT NOT NULL,
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS crash_reports (
    id TEXT PRIMARY KEY,
    user_id TEXT,
    family_id TEXT,
    role TEXT,
    message TEXT,
    stack TEXT,
    device TEXT,
    app_version TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_family_members_family_id ON family_members(family_id);
CREATE INDEX IF NOT EXISTS idx_invites_code ON invites(invite_code);
CREATE INDEX IF NOT EXISTS idx_bind_requests_family_status ON bind_requests(family_id, status);
CREATE INDEX IF NOT EXISTS idx_audit_logs_family_created ON audit_logs(family_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_crash_reports_created ON crash_reports(created_at DESC);
