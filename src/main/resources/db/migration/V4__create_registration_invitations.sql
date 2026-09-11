create table auth_registration_invitations (
    invitation_id uuid primary key,
    token_digest char(64) not null unique,
    created_by uuid not null references auth_accounts,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    revoked_at timestamp with time zone,
    used_at timestamp with time zone,
    used_by uuid references auth_accounts,
    check ((used_at is null) = (used_by is null))
);
create index auth_registration_invitations_issuer
    on auth_registration_invitations (created_by, created_at, invitation_id);
