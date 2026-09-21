create table content_github_webhook_deliveries (
    client_id text not null,
    delivery_id uuid not null,
    received_at timestamptz not null default current_timestamp,
    primary key (client_id, delivery_id)
);

create table content_github_access_epochs (
    client_id text not null,
    installation_id bigint not null check (installation_id >= 0),
    repository_id bigint not null check (repository_id >= 0),
    epoch bigint not null default 0 check (epoch >= 0),
    primary key (client_id, installation_id, repository_id),
    check ((installation_id > 0 and repository_id = 0) or (installation_id = 0 and repository_id > 0))
);

create table content_github_authorization_epochs (
    client_id text not null,
    github_user_id bigint not null check (github_user_id > 0),
    epoch bigint not null default 0 check (epoch >= 0),
    primary key (client_id, github_user_id)
);
