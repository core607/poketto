alter table workspaces add column public_slug text;
update workspaces set public_slug = case when is_default then 'home' else 'space-' || workspace_id::text end;
alter table workspaces alter column public_slug set not null;
alter table workspaces alter column public_slug set default ('space-' || gen_random_uuid()::text);
alter table workspaces add constraint workspaces_slug_format check (public_slug ~ '^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$');
create unique index workspaces_slug_unique on workspaces(public_slug);
alter table workspaces add column public_delivery boolean not null default false;
update workspaces set public_delivery=true where is_default;

create table content_repository_bindings (
    workspace_id uuid primary key references workspaces,
    canonical_uri text not null unique,
    provider_identity text not null unique,
    sealed_credentials bytea not null,
    updated_at timestamptz not null default current_timestamp
);

create table space_creation_attempts (
    account_id uuid not null references auth_accounts,
    request_id uuid not null,
    workspace_id uuid not null unique,
    display_name text not null,
    public_slug text not null,
    canonical_uri text not null,
    sealed_credentials bytea,
    stage text not null check (stage in ('VALIDATING','FAILED','READY')),
    failure_code text,
    lease_id uuid not null,
    updated_at timestamptz not null default current_timestamp,
    primary key(account_id,request_id),
    check ((stage='READY') = (sealed_credentials is null))
);
