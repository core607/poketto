alter table content_repository_bindings add column github_binding_version bigint not null default 1
    check (github_binding_version > 0);
alter table content_repository_bindings add column github_revoked boolean not null default false;
