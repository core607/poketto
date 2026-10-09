create table game_accounts (
    account_id uuid primary key,
    next_request bigint not null default 1 check (next_request > 0)
);

create table game_saves (
    save_id uuid primary key,
    account_id uuid not null,
    creation_request bigint not null check (creation_request > 0),
    creation_digest varchar(71) not null,
    workspace_id uuid not null,
    article_id uuid not null,
    package_version varchar(71) not null,
    state text check (octet_length(state) <= 32768),
    revision bigint not null default 0 check (revision >= 0),
    seed bigint not null check (seed between 0 and 4294967295),
    last_digest varchar(71),
    last_result text check (octet_length(last_result) <= 98304),
    pending_digest varchar(71),
    pending_id uuid,
    pending_until timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique(account_id, creation_request),
    check ((pending_digest is null) = (pending_until is null)),
    check ((pending_digest is null) = (pending_id is null))
);
create index game_saves_account on game_saves(account_id, updated_at desc);
