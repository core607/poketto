create table content_github_grants (
    account_id uuid primary key references auth_accounts,
    client_id text not null,
    github_user_id bigint not null check (github_user_id > 0),
    github_login text not null,
    version bigint not null check (version > 0),
    state text not null check (state in ('ACTIVE', 'REFRESHING', 'REAUTHORIZATION', 'REVOKED')),
    sealed_tokens bytea,
    access_expires_at timestamptz,
    refresh_expires_at timestamptz,
    refresh_lease uuid,
    refresh_deadline timestamptz,
    updated_at timestamptz not null,
    check ((state in ('ACTIVE', 'REFRESHING')) = (sealed_tokens is not null)),
    check ((state = 'REFRESHING') = (refresh_lease is not null)),
    check ((state = 'REFRESHING') = (refresh_deadline is not null)),
    check ((access_expires_at is null) = (refresh_expires_at is null))
);

create index content_github_grants_owner on content_github_grants(client_id, github_user_id);
