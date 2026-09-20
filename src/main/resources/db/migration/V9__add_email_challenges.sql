create table auth_email_challenges (
    challenge_id uuid primary key,
    email text not null,
    purpose text not null check (purpose in ('SIGNUP','BIND','RECOVERY')),
    account_id uuid references auth_accounts,
    code_digest char(64) not null,
    expires_at timestamptz not null,
    delivered_at timestamptz,
    consumed_at timestamptz,
    failed_attempts integer not null default 0 check (failed_attempts between 0 and 5),
    unique (email,purpose),
    check (purpose<>'BIND' or account_id is not null),
    check (purpose<>'SIGNUP' or account_id is null),
    check (purpose<>'RECOVERY' or delivered_at is null or account_id is not null)
);
create index auth_email_challenges_expiration on auth_email_challenges(expires_at);

-- The global reservation is locked first; no provider I/O runs inside this transaction.
create table auth_email_limits (
    bucket text primary key,
    window_start timestamptz not null,
    expires_at timestamptz not null,
    send_count integer not null check (send_count > 0),
    last_sent_at timestamptz not null
);
create index auth_email_limits_expiration on auth_email_limits(expires_at);
