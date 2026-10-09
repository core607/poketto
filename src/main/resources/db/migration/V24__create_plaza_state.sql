-- Account-owned state, never an article projection. Cross-module identities have
-- no foreign-key locks; authorization is revalidated while holding the account.
create table machine_account_grants (
    key_id uuid primary key references auth_api_keys on delete cascade,
    account_id uuid not null references auth_accounts,
    permissions text[] not null default '{}',
    check (permissions <@ array['POCKET','COMMENT','WISH','GAME_SAVE']::text[])
);
create index machine_account_grants_holder on machine_account_grants(account_id);

create table plaza_notes (
    note_id uuid primary key,
    account_id uuid not null,
    request_id uuid not null,
    body text not null check (char_length(body) between 0 and 1000),
    deleted boolean not null default false,
    client_name text not null check (char_length(client_name) <= 80),
    created_at timestamptz not null,
    unique(account_id, request_id)
);
create index plaza_notes_account on plaza_notes(account_id, created_at desc, note_id);

create table plaza_discoveries (
    account_id uuid not null,
    tag text not null check (char_length(tag) between 1 and 64),
    discovered_at timestamptz not null,
    primary key(account_id, tag)
);
create index plaza_discoveries_tag on plaza_discoveries(tag);
