create table oauth_clients (
    client_id text primary key,
    client_name text not null,
    redirect_uris text[] not null,
    created_at timestamptz not null default current_timestamp
);
create table oauth_connections (
    key_id uuid primary key references auth_api_keys on delete cascade,
    client_id text not null references oauth_clients,
    workspace_id uuid not null references workspaces on delete cascade,
    account_id uuid not null references auth_accounts on delete cascade,
    scopes text not null,
    resource text not null,
    created_at timestamptz not null,
    expires_at timestamptz not null
);
create table oauth_codes (
    digest char(64) primary key,
    key_id uuid not null references oauth_connections on delete cascade,
    redirect_uri text not null,
    challenge text not null,
    expires_at timestamptz not null,
    used_at timestamptz
);
create table oauth_access_tokens (
    digest char(64) primary key,
    key_id uuid not null references oauth_connections on delete cascade,
    expires_at timestamptz not null
);
create table oauth_refresh_tokens (
    digest char(64) primary key,
    key_id uuid not null references oauth_connections on delete cascade,
    expires_at timestamptz not null,
    used_at timestamptz
);
create index oauth_connections_workspace on oauth_connections(workspace_id);
create index oauth_access_key on oauth_access_tokens(key_id);
create index oauth_refresh_key on oauth_refresh_tokens(key_id);
