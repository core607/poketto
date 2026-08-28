create table workspaces (
    workspace_id uuid primary key,
    display_name text not null check (btrim(display_name) <> ''),
    is_default boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp
);

create unique index workspaces_one_default
    on workspaces (is_default)
    where is_default;
