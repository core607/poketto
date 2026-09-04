create table auth_accounts (
    account_id uuid primary key,
    login_name text not null unique check (login_name ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
    password_hash text not null,
    instance_admin boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp
);

create table auth_initialization (
    singleton boolean primary key check (singleton),
    initialized_at timestamp with time zone
);
insert into auth_initialization (singleton) values (true);

create table auth_memberships (
    workspace_id uuid not null references workspaces,
    account_id uuid not null references auth_accounts,
    role text not null check (role in ('OWNER', 'MEMBER')),
    suspended_at timestamp with time zone,
    primary key (workspace_id, account_id)
);

create table auth_invitations (
    invitation_id uuid primary key,
    workspace_id uuid not null references workspaces,
    token_digest char(64) not null unique,
    created_by uuid not null references auth_accounts,
    created_at timestamp with time zone not null default current_timestamp,
    expires_at timestamp with time zone not null,
    revoked_at timestamp with time zone,
    used_at timestamp with time zone,
    used_by uuid references auth_accounts,
    check ((used_at is null) = (used_by is null))
);

create table auth_api_keys (
    key_id uuid primary key,
    workspace_id uuid not null,
    account_id uuid not null,
    created_by uuid not null references auth_accounts,
    token_digest char(64) not null unique,
    capabilities text[] not null check (capabilities <@ array[
        'READ_PRIVATE', 'WRITE_PRIVATE', 'PUBLISH', 'MANAGE_KEYS', 'EXECUTE_REPOSITORY'
    ]::text[]),
    created_at timestamp with time zone not null default current_timestamp,
    revoked_at timestamp with time zone,
    foreign key (workspace_id, account_id) references auth_memberships
);
create index auth_keys_workspace on auth_api_keys (workspace_id, account_id);
create index auth_invitations_workspace on auth_invitations (workspace_id);
